package io.scos.collection.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.projection.MemoryProjection;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.googledrive.internal.FakeGoogleServer;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.internal.FakeJiraServer;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.notion.internal.FakeNotionServer;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.FakeHttpServer.Recorded;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 합성 공급자와 기존 메모리 조립을 공유해 본문 수집부터 실제 투영까지 실행한다. */
final class AccessDisabledScenario implements AutoCloseable {
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final Duration TICK = Duration.ofSeconds(1);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final MemoryProjection projection = new MemoryProjection();
  private final MemoryRunStore runs = MemoryRunStore.create();
  private final MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
  private final SyntheticProvider provider;
  private final CollectionRuntime runtime;
  private int nextRun;
  private int staged;
  private int merged;
  private int transactions;

  private AccessDisabledScenario(SyntheticProvider provider) {
    this(provider, false);
  }

  private AccessDisabledScenario(SyntheticProvider provider, boolean collectAccess) {
    this.provider = provider;
    projection.clock.advance(Duration.between(T0, provider.collectedAt()));
    projection.sources.save(provider.instance());
    projection.transactions(countingTransactions());
    runtime =
        MemoryCollectionRuntime.over(projection.clock, projection.sources, projection.claims, runs)
            .adapter(provider.adapter())
            .rawItems(projection.rawItems)
            .outbox(projection.outbox)
            .checkpoints(checkpoints)
            .accessObservations(observations())
            .collectAccess(collectAccess)
            .runIds(() -> new RunId("compat-" + ++nextRun))
            .build();
  }

  static AccessDisabledScenario slack() throws IOException {
    var clock = new MutableClock(T0);
    var server =
        new FakeSlackServer(
            Path.of(
                "scos-collection/src/test/resources/io/scos/collection/internal/slack-access-dataset.json"));
    return new AccessDisabledScenario(
        new SyntheticProvider(
            SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, T0),
            SlackAssembly.adapter(server.baseUrl(), server.token(), clock),
            T0,
            SyntheticProvider.noEdit("slack"),
            server));
  }

  static AccessDisabledScenario drive() {
    return new AccessDisabledScenario(
        GoogleDriveAssembly.synthetic(new MutableClock(Instant.parse("2026-10-15T00:00:00Z"))));
  }

  static AccessDisabledScenario jira() {
    return new AccessDisabledScenario(
        JiraAssembly.synthetic(new MutableClock(Instant.parse("2026-10-15T00:00:00Z"))));
  }

  static AccessDisabledScenario notion() throws IOException {
    return new AccessDisabledScenario(notionProvider());
  }

  static AccessDisabledScenario notionWithAccessCollection() throws IOException {
    return new AccessDisabledScenario(notionProvider(), true);
  }

  private static SyntheticProvider notionProvider() throws IOException {
    var server =
        new FakeNotionServer(
            JSON.readTree(
                Path.of(
                    "scos-collection/src/test/resources/io/scos/collection/internal/notion-disabled-dataset.json")));
    return new SyntheticProvider(
        NotionAssembly.instance(
            "{\"rootPageIds\":[\"root-wiki\"],\"discoverSharedPages\":true}", T0),
        NotionAssembly.adapter(server.baseUrl(), server.token(), new MutableClock(T0)),
        T0,
        SyntheticProvider.noEdit("notion"),
        server);
  }

  private AccessObservationStore observations() {
    var delegate = runs.accessObservations();
    return new AccessObservationStore() {
      @Override
      public void stage(RunId run, AclPage page) {
        staged++;
        delegate.stage(run, page);
      }

      @Override
      public AccessMerge merge(
          RunId run,
          SourceInstanceId source,
          AccessMerge mode,
          IdentityCoverage identityCoverage,
          Instant at,
          Runnable guard) {
        merged++;
        return delegate.merge(run, source, mode, identityCoverage, at, guard);
      }

      @Override
      public void discard(RunId run) {
        delegate.discard(run);
      }
    };
  }

  private TransactionOperations countingTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        transactions++;
        return TransactionOperations.withoutTransaction().execute(action);
      }
    };
  }

  void collect() {
    runtime.collect(provider.instance().id(), CollectionMode.INCREMENTAL, CancellationToken.none());
    var run = runs.latest(provider.instance().id()).orElseThrow();
    if (!run.status().isSuccess() || (run.upserted() == 0 && run.unchanged() == 0))
      throw new IllegalStateException("given 준비 실패: 본문 수집 성공이 필요하다 — " + run);
    projection.clock.advance(TICK);
  }

  ProjectionRun.Report project() {
    return projection
        .projection(FixedEmbedder.zero(4), new SimpleMeterRegistry())
        .runDue(CancellationToken.none())
        .getFirst();
  }

  List<String> slackRequests() {
    return ((FakeSlackServer) provider.server()).requests();
  }

  record Request(String path, Map<String, String> params) {}

  List<Request> requests() {
    List<Recorded> recorded =
        switch (provider.server()) {
          case FakeGoogleServer server -> server.requests();
          case FakeJiraServer server -> server.requests();
          case FakeNotionServer server -> server.requests();
          default -> throw new IllegalStateException("Slack은 원문 요청을 사용한다");
        };
    return recorded.stream()
        .map(
            r ->
                new Request(
                    r.path().replaceFirst("^/_(drive|docs|sheets|slides|oauth)", ""), r.params()))
        .toList();
  }

  static JsonNode expected(String kind) throws IOException {
    return JSON.readTree(Path.of("../harness/fixtures", kind, "expected-raw-items.json"));
  }

  static List<Request> expectedRequests(String kind) throws IOException {
    return expected(kind)
        .get("requests")
        .valueStream()
        .map(r -> new Request(r.get("path").asString(), stringMap(r.get("params"))))
        .toList();
  }

  private static Map<String, String> stringMap(JsonNode node) {
    Map<String, String> result = new HashMap<>();
    node.properties().forEach(p -> result.put(p.getKey(), p.getValue().asString()));
    return Map.copyOf(result);
  }

  JsonNode checkpoint() {
    var stored = checkpoints.read(provider.instance().id());
    var checkpoint = stored.checkpoint().orElseThrow();
    return JSON.createObjectNode()
        .put("revision", Math.toIntExact(stored.revision()))
        .set(
            "checkpoint",
            JSON.createObjectNode()
                .put("schemaVersion", checkpoint.schemaVersion())
                .set("value", JSON.readTree(checkpoint.valueJson())));
  }

  record Version(String id, String version) {}

  List<Version> versions() {
    return projection
        .rawItems
        .listActive(provider.instance().id(), 1000, Optional.empty())
        .items()
        .stream()
        .map(r -> new Version(r.externalId(), r.sourceVersion()))
        .toList();
  }

  static List<Version> expectedVersions(String kind) throws IOException {
    return expected(kind)
        .get("items")
        .valueStream()
        .map(
            i ->
                new Version(
                    i.get("key").get("externalId").asString(), i.get("sourceVersion").asString()))
        .toList();
  }

  record AccessWrites(int rawAcls, int observations, int groups, int identities, int merges) {}

  AccessWrites accessWrites() {
    var source = provider.instance().id();
    var ids = versions().stream().map(Version::id).toList();
    var directory = runs.accessObservations().snapshot(source);
    return new AccessWrites(
        projection.rawAcls.get(source, ids).size(),
        staged,
        directory.groups().size(),
        directory.identities().size(),
        merged);
  }

  void requireNoAccessWrites() {
    if (!accessWrites().equals(new AccessWrites(0, 0, 0, 0, 0)))
      throw new IllegalStateException("given 준비 실패: 접근 관측이 없는 저장소가 필요하다");
  }

  record Projected(int units, int aclRows, int candidates, int transactions) {}

  Projected projected() {
    var units = projection.storedUnits();
    var ids = units.stream().map(u -> u.documentId()).toList();
    return new Projected(
        units.size(),
        projection.acls.get(MemoryProjection.SPACE, ids).size(),
        projection.syncedAcls().size(),
        transactions);
  }

  @Override
  public void close() throws Exception {
    provider.close();
  }
}
