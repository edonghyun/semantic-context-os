package io.scos.collection.internal;

import io.scos.collection.internal.memory.MemoryAccessObservationStore;
import io.scos.collection.internal.memory.MemoryRawAclStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestMetrics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import tools.jackson.databind.ObjectMapper;

/** 접근 단계의 실제 저장/점유/커밋과 외부 어댑터 경계를 연결한다. */
final class AccessCollectionScenario implements AutoCloseable {
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant T1 = T0.plusSeconds(1);
  static final Duration TTL = Duration.ofMinutes(1);
  private static final String LOCAL_URL = "http://127.0.0.1:1";
  private static final String TOKEN = "synthetic-token";
  private final MemoryAclStore documentAcls = new MemoryAclStore();
  private final MemoryUnitMaintenance units =
      new MemoryUnitMaintenance(new MemoryUnitStore(documentAcls), new MemoryVectorIndex());
  final CommitContext.Memory memory =
      CommitContext.Memory.create(new MemoryRawAclStore(units, documentAcls));
  final MutableClock clock = new MutableClock(T1);
  final CancellationToken cancel = CancellationToken.none();
  final RunId run = new RunId("access-run");
  final SourceDescriptor source;
  final WorkClaim claim;
  LeaseHeartbeat heartbeat;
  final DomainPolicy domains;
  final ProviderCallExecutor calls =
      ProviderCallExecutor.defaults(clock, TestMetrics.providerCalls());
  private final AccessAdapter adapter;
  private boolean enabled = true;
  private boolean failObservationRead;
  private boolean failRawRead;
  private int rawReads;
  private Runnable afterObservationRead = () -> {};
  private Runnable afterRawRead = () -> {};
  private Runnable afterMissingCount = () -> {};

  AccessCollectionScenario(String kind) {
    this(kind, "access-source");
  }

  AccessCollectionScenario(String kind, String instance) {
    var declaration = declaration(kind);
    source =
        SourceInstances.of(new SourceInstanceId(instance), declaration.kind(), "{}", "env:FAKE", T0)
            .descriptor();
    domains = new DomainPolicy(Set.of("example.com"), Map.of(source.id(), "example.com"));
    memory
        .sources()
        .save(SourceInstances.of(source.id(), declaration.kind(), "{}", "env:FAKE", T0));
    claim = memory.claims().tryClaim(source.id(), new OwnerId("worker"), TTL, T1).orElseThrow();
    memory.runs().start(run, source.id(), T1);
    heartbeat =
        new LeaseHeartbeat(
            memory.claims(),
            claim,
            TTL,
            clock,
            new ManualTaskScheduler(),
            TestMetrics.collection());
    adapter = new AccessAdapter(declaration);
  }

  private SourceAdapter declaration(String kind) {
    return switch (kind) {
      case "google-drive" ->
          GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, clock);
      case "jira" -> JiraAssembly.adapter(LOCAL_URL, TOKEN, clock);
      case "slack" -> SlackAssembly.adapter(LOCAL_URL, TOKEN, clock);
      case "tiro" -> TiroAssembly.adapter(LOCAL_URL, TOKEN, clock);
      case "notion" -> NotionAssembly.adapter(LOCAL_URL, TOKEN, clock);
      case "custom-refresh" ->
          new DeclaredAdapter(
              "custom-refresh",
              AccessPolicy.supported(Set.of("gdrive.file"), Set.of("gdrive.file")));
      case "custom-local" ->
          new DeclaredAdapter(
              "custom-local",
              AccessPolicy.supported(Set.of("gdrive.file"), Set.of())
                  .collecting(
                      (input, sink) -> {},
                      (source, id, at) ->
                          Optional.of(
                              RawAcl.observed(
                                  Status.OK,
                                  Set.of(AccessKey.parse("group:" + source.id() + ":G1")),
                                  at))));
      case "custom-nonrefresh" ->
          new DeclaredAdapter(
              "custom-nonrefresh", AccessPolicy.supported(Set.of("gdrive.file"), Set.of()));
      default -> throw new IllegalArgumentException(kind);
    };
  }

  void active(String id, String type) {
    memory
        .rawItems()
        .apply(
            source.id(),
            RawItems.item(id).resourceType(type).metadata("{\"id\":\"" + id + "\"}").upsert());
  }

  void storedIssueContent(String storage) {
    String body = "{\"id\":\"10001\",\"fields\":{\"project\":{\"id\":\"P1\"}}}";
    var item = RawItems.item("issue:10001").resourceType("jira.issue").inlineJson(body);
    if (storage.equals("blob"))
      item.content(
          new RawContent.Blob(
              "application/json",
              "a".repeat(64),
              body.getBytes(StandardCharsets.UTF_8).length,
              Optional.of(body.getBytes(StandardCharsets.UTF_8))));
    if (storage.equals("missingBlob"))
      item.content(new RawContent.Blob("application/json", "b".repeat(64), 60, Optional.empty()));
    if (storage.equals("absent")) item.content(Optional.empty());
    var sourceId =
        storage.equals("otherSource") ? new SourceInstanceId("another-jira") : source.id();
    memory.rawItems().apply(sourceId, item.upsert());
    if (storage.equals("deleted")) deleted("issue:10001");
  }

  Optional<String> storedContent(String id) {
    return input().storedContent().apply(id);
  }

  void existingDocument() {
    units.upsert(
        new SpaceId("legacy"),
        List.of(
            ContextUnits.unit("legacy-unit").source(source.id().value()).inDocument("P1").build()));
  }

  void afterMissingCount(Runnable action) {
    afterMissingCount = action;
  }

  void changeSourceConfiguration() {
    memory
        .sources()
        .save(SourceInstances.of(source.id(), source.kind(), "{\"changed\":true}", "env:FAKE", T0));
  }

  void finishRun() {
    memory
        .runs()
        .finish(
            run,
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            T1,
            0,
            AccessResult.SKIPPED,
            Optional.empty());
  }

  void heartbeatStorageFails() {
    heartbeat.stop();
    var failing =
        new MemoryWorkClaimPort() {
          @Override
          public synchronized WorkClaim renew(WorkClaim current, Duration ttl, Instant now) {
            throw new IllegalStateException("injected heartbeat storage failure");
          }
        };
    heartbeat =
        new LeaseHeartbeat(
            failing, claim, TTL, clock, new ManualTaskScheduler(), TestMetrics.collection());
    heartbeat.renew();
  }

  void duringResolution(Runnable action) {
    adapter.duringResolution = action;
  }

  void activeFiles(int count) {
    IntStream.range(0, count).forEach(i -> active("file:F" + i, "gdrive.file"));
  }

  void withAcl(String id, Status status) {
    memory.rawAcls().observe(source.id(), id, RawAcl.observed(status, Set.of(), T0), T0);
  }

  void observedAt(String id, Instant at) {
    memory.rawAcls().observe(source.id(), id, RawAcl.observed(Status.OK, Set.of(), at), at);
  }

  void deleted(String id) {
    memory.rawItems().tombstoneSelected(source.id(), Set.of(id), T0);
  }

  void bodyObserved(String... ids) {
    memory.runs().observe(run, List.of(ids));
  }

  void bodyProgress() {
    memory
        .checkpoints()
        .advance(source.id(), 0, new Checkpoint(1, "{\"cursor\":\"body-cursor\"}"), T0);
    bodyObserved("C1:1700000000.000001", "C2:1700000000.000002");
  }

  boolean accessObservationsConflicted() {
    return ((MemoryAccessObservationStore) memory.accessObservations()).staged(run).conflicted();
  }

  void disabled() {
    enabled = false;
  }

  void unimplementedAccess() {
    var declaration = adapter.declaration.accessPolicy();
    adapter.access =
        AccessPolicy.supported(declaration.carrierKinds(), Set.of(), declaration::documentOf);
  }

  void unobservedResolution() {
    adapter.unobservedResolution = true;
  }

  void pages(AclPage... pages) {
    adapter.pages.addAll(List.of(pages));
  }

  void duringAccess(Runnable action) {
    adapter.duringAccess = action;
  }

  void budgetForFirstTarget() {
    adapter.budgetLimited = true;
  }

  void failAfterPages() {
    adapter.afterPages =
        () -> {
          throw new IllegalStateException("provider failed after page");
        };
  }

  void failProviderAndExpiryRead() {
    adapter.afterPages =
        () -> {
          failRawRead = true;
          throw providerFailure;
        };
  }

  private final RuntimeException providerFailure = new IllegalStateException("provider failed");

  RuntimeException providerFailure() {
    return providerFailure;
  }

  void cancelAfterFirstRawPage() {
    afterRawRead = cancel::cancel;
  }

  int rawReads() {
    return rawReads;
  }

  int resolutions() {
    return adapter.resolutions;
  }

  void cancelOnExpiryRead() {
    afterRawRead =
        new Runnable() {
          private int reads;

          @Override
          public void run() {
            if (++reads == 2) cancel.cancel();
          }
        };
  }

  void expireDirectly() {
    new AclCommit(memory.ports().stores(), SourceAdapters.of(List.of(adapter)), clock, true)
        .expireBefore(claim, run, source, List.of("file:F1"), T1.minusSeconds(86400));
  }

  void takeover() {
    memory.claims().release(claim, clock.instant());
    if (memory
        .claims()
        .tryClaim(source.id(), new OwnerId("successor"), TTL, clock.instant())
        .isEmpty()) throw new IllegalStateException("given 준비 실패: 점유 인계가 실패했다");
  }

  void lostClaimBeforeObservationFailure() {
    takeover();
    failObservationRead = true;
  }

  void lostClaimBeforeRawReadFailure() {
    afterObservationRead = this::takeover;
    failRawRead = true;
  }

  void lostClaimAfterRawReadBeforeResolutionFailure() {
    afterRawRead = this::takeover;
    adapter.duringResolution =
        () -> {
          throw new IllegalStateException("resolution unavailable");
        };
  }

  private CollectionStores readBoundaries() {
    var runs =
        interceptRead(
            RunStore.class,
            memory.runs(),
            "observations",
            () -> {
              if (failObservationRead) throw new IllegalStateException("observations unavailable");
            },
            afterObservationRead);
    var raw =
        interceptRead(
            RawItemStore.class,
            memory.rawItems(),
            "accessCandidates",
            () -> {
              rawReads++;
              if (failRawRead) throw new IllegalStateException("raw read unavailable");
            },
            afterRawRead);
    var aclReads =
        interceptRead(
            RawAclStore.class,
            memory.rawAcls(),
            "documentsMissingAcl",
            () -> {},
            afterMissingCount);
    return memory.ports().withReads(runs, raw, aclReads).stores();
  }

  private <T> T interceptRead(
      Class<T> type, T delegate, String read, Runnable before, Runnable after) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              boolean matches = method.getName().equals(read);
              if (matches) before.run();
              try {
                Object result = method.invoke(delegate, args);
                if (matches) after.run();
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            }));
  }

  Set<String> unreadableAclIds() {
    return memory.runs().get(run).orElseThrow().aclUnreadable();
  }

  AccessResult collect() {
    var stage =
        new AccessCollection(
            readBoundaries(),
            SourceAdapters.of(List.of(adapter)),
            calls,
            domains,
            boundedClock(),
            enabled,
            new ProjectionSpaces(Map.of(), new SpaceId("legacy"), new ObjectMapper()),
            AccessCollection.Options.DEFAULTS);
    return stage.collect(source, adapter, claim, run, heartbeat, cancel).result();
  }

  // 커서 종료 변이가 무한히 돌아도 PIT timeout 대신 즉시 단언 실패로 드러낸다.
  private Clock boundedClock() {
    return new Clock() {
      private int reads;

      @Override
      public ZoneId getZone() {
        return clock.getZone();
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        if (++reads > 1_000) throw new AssertionError("접근 원본 순회가 종료되지 않았다");
        return clock.instant();
      }
    };
  }

  List<String> targets() {
    return adapter.inputs.stream().flatMap(input -> input.externalIds().stream()).toList();
  }

  int accessCalls() {
    return adapter.inputs.size();
  }

  AccessInput input() {
    return adapter.inputs.getFirst();
  }

  Map<String, RawAcl.Observed> acls(String... ids) {
    return memory.rawAcls().get(source.id(), List.of(ids));
  }

  void conflictingPages() {
    active("file:F2", "gdrive.file");
    pages(
        new AclPage(
            Map.of(),
            List.of(),
            List.of(
                new SourceIdentityRecord(
                    "U7", Optional.of("u7@example.com"), true, false, false, T1))),
        new AclPage(
            Map.of(),
            List.of(),
            List.of(new SourceIdentityRecord("U7", Optional.empty(), false, false, false, T1))),
        new AclPage(
            Map.of("file:F2", RawAcl.observed(Status.FAILED, Set.of(), T1)), List.of(), List.of()));
  }

  @Override
  public void close() {
    heartbeat.stop();
  }

  /** 원천 선언·로컬 해석은 실제 어댑터가 소유하고, 접근 I/O만 이 대역이 맡는다. */
  private static final class AccessAdapter implements SourceAdapter {
    private final SourceAdapter declaration;
    private AccessPolicy access;
    private final List<AccessInput> inputs = new ArrayList<>();
    private final List<AclPage> pages = new ArrayList<>();
    private Runnable duringAccess = () -> {};
    private Runnable duringResolution = () -> {};
    private Runnable afterPages = () -> {};
    private boolean unobservedResolution;
    private boolean budgetLimited;
    private int resolutions;

    AccessAdapter(SourceAdapter declaration) {
      this.declaration = declaration;
      access = declaration.accessPolicy().collecting(this::collectAccess, this::aclFromExternalId);
    }

    @Override
    public String kind() {
      return declaration.kind();
    }

    @Override
    public AccessPolicy accessPolicy() {
      return access;
    }

    private void collectAccess(AccessInput input, AclSink sink) {
      inputs.add(input);
      duringAccess.run();
      if (budgetLimited) {
        sink.accept(
            new AclPage(
                Map.of(
                    input.externalIds().getFirst(), RawAcl.observed(Status.FAILED, Set.of(), T1)),
                List.of(),
                List.of()));
        throw new IllegalStateException("run budget exhausted");
      }
      pages.forEach(sink::accept);
      afterPages.run();
    }

    private Optional<RawAcl> aclFromExternalId(SourceDescriptor source, String id, Instant at) {
      resolutions++;
      duringResolution.run();
      if (unobservedResolution) return Optional.of(RawAcl.notObserved());
      return declaration.accessPolicy().localAcl(source, id, at);
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {
      throw new AssertionError("접근 보충은 history/replies 본문 수집을 호출하면 안 된다");
    }
  }

  private static final class DeclaredAdapter implements SourceAdapter {
    private final String kind;
    private final AccessPolicy policy;

    DeclaredAdapter(String kind, AccessPolicy policy) {
      this.kind = kind;
      this.policy = policy;
    }

    @Override
    public String kind() {
      return kind;
    }

    @Override
    public AccessPolicy accessPolicy() {
      return policy;
    }

    @Override
    public void validateConfig(SourceConfig config) {}

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return CollectionCoverage.INCREMENTAL;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {}
  }
}
