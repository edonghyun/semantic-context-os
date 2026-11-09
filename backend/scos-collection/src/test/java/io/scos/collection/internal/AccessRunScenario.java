package io.scos.collection.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryAccessObservationStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryIdentitySeed;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.postgres.PostgresIdentityStore;
import io.scos.support.FakeHttpServer;
import io.scos.support.ManualTaskScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 런타임과 저장 어댑터를 연결하고 공급자 I/O 및 병합 경계의 장애만 주입한다. */
public final class AccessRunScenario implements AutoCloseable {
  public static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  public static final Instant T1 = T0.plusSeconds(1);
  public static final SourceInstanceId SOURCE = new SourceInstanceId("access-source");
  private static final Duration TTL = Duration.ofSeconds(30);
  private static final int MAX_CLAIM_CHECKS_PER_RUN = 1_000;
  private static final RunId RUN = new RunId("access-run");
  private final MutableClock clock = new MutableClock(T1);
  private final CancellationToken cancel = CancellationToken.none();
  private final ScriptedAdapter adapter = new ScriptedAdapter();
  private final CollectionStores stores;
  private final IdentityStore identities;
  private final JdbcClient jdbc;
  private final TransactionOperations handoffs;
  private final Supplier<CollectionRuntime> runtime;
  private Runnable beforeMerge = () -> {};
  private Runnable beforeFallback = () -> {};
  private Runnable afterMerge = () -> {};
  private CollectionCoverage coverage = CollectionCoverage.INCREMENTAL;
  private boolean bodyPage;
  private boolean invalidConfiguration;
  private List<RejectedItem> bodyRejections = List.of();
  private Runnable beforeBodyPage = () -> {};
  private Runnable afterBody = () -> {};
  private Runnable afterAccess = () -> {};
  private int accessCalls;
  private boolean collectAccess = true;
  private boolean unsupported;
  private IdentityCoverage identityCoverage = IdentityCoverage.PARTIAL;
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private boolean failRenew;
  private Runnable afterClaimCheck = () -> {};
  private final List<AclPage> pages = new ArrayList<>();
  private int completionFailures;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private Set<String> omittedAcls = Set.of();
  private Runnable afterStagedPage = () -> {};
  private List<String> targetIds = List.of();
  private WorkClaim costClaim;
  private boolean rejectCost;
  private FakeHttpServer driveServer;
  private AccessPolicy remoteAccess;
  private String sourceKind = "google-drive";

  public void scopedUnsupportedAccess() {
    sourceKind = "notion";
    remoteAccess = AccessPolicy.scoped("notion");
    stores.sources().orElseThrow().save(SourceInstances.of(SOURCE, "notion", "{}", "env:FAKE", T0));
  }

  public void localAclStorageFailsDuringSelection() {
    remoteAccess =
        AccessPolicy.supported(Set.of("gdrive.file"), Set.of("gdrive.file"))
            .collecting(
                adapter::collectAccess,
                (source, id, at) -> Optional.of(RawAcl.observed(Status.OK, Set.of(), at)));
    afterStagedPage =
        () -> {
          throw new CannotGetJdbcConnectionException("injected local ACL storage failure");
        };
  }

  public void realDrivePermissionFailure() {
    try {
      driveServer = new FakeHttpServer();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    driveServer.on(
        "POST",
        "/_oauth/token",
        request ->
            FakeHttpServer.ok("{\"access_token\":\"synthetic-access\",\"expires_in\":3600}"));
    driveServer.on(
        "GET",
        "/_drive/drive/v3/files/F1/permissions",
        request ->
            FakeHttpServer.ok(
                request.params().containsKey("pageToken")
                    ? "{\"permissions\":[]}"
                    : "{\"permissions\":[],\"nextPageToken\":\"p2\"}"));
    driveServer.on(
        "GET",
        "/_drive/drive/v3/files/F2/permissions",
        request -> FakeHttpServer.status(400, "{}"));
    driveServer.limitRequests(5, FakeHttpServer.status(400, "{}"));
    connectDriveAccess();
    stores.rawItems().apply(SOURCE, RawItems.item("file:F2").resourceType("gdrive.file").upsert());
  }

  private void connectDriveAccess() {
    remoteAccess =
        GoogleDriveAssembly.adapter(
                driveServer.baseUrl(), GoogleDriveAssembly.CREDENTIAL_JSON, clock)
            .accessPolicy();
    stores
        .sources()
        .orElseThrow()
        .save(
            SourceInstances.of(
                SOURCE,
                "google-drive",
                "{\"accountEmail\":\"reader@example.com\",\"permissionVisibility\":\"VERIFIED_FULL\"}",
                GoogleDriveAssembly.CREDENTIAL_REF,
                T0));
  }

  public void emptyCostTargets() {
    stores.rawItems().tombstoneSelected(SOURCE, Set.of("file:F1"), T0);
  }

  public void invalidStoredCost(String assignments) {
    jdbc.sql("UPDATE collection_runs SET " + assignments + " WHERE run_id = 'access-run'").update();
  }

  public void traceCostWrites() {
    jdbc.sql("CREATE TABLE cost_write_trace (seq bigserial, xid bigint, event text)").update();
    jdbc.sql(
            """
        CREATE FUNCTION trace_cost_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF TG_TABLE_NAME = 'source_groups' THEN
            INSERT INTO cost_write_trace(xid, event) VALUES (txid_current(), 'merge');
          ELSE
            INSERT INTO cost_write_trace(xid, event) VALUES (txid_current(),
              CASE WHEN NEW.status <> 'running' THEN 'terminal' ELSE 'cost' END);
          END IF;
          RETURN NULL;
        END $$
        """)
        .update();
    jdbc.sql(
            "CREATE TRIGGER trace_cost_write AFTER UPDATE OF access_target_count, status ON collection_runs FOR EACH ROW EXECUTE FUNCTION trace_cost_write()")
        .update();
    jdbc.sql(
            "CREATE TRIGGER trace_cost_write AFTER INSERT OR UPDATE ON source_groups FOR EACH ROW EXECUTE FUNCTION trace_cost_write()")
        .update();
  }

  public List<String> costWriteOrder() {
    return jdbc.sql("SELECT event FROM cost_write_trace ORDER BY seq").query(String.class).list();
  }

  public int costWriteTransactions() {
    return jdbc.sql("SELECT count(DISTINCT xid) FROM cost_write_trace")
        .query(Integer.class)
        .single();
  }

  public void stopTracingCostWrites() {
    jdbc.sql("DROP TRIGGER trace_cost_write ON source_groups").update();
    jdbc.sql("DROP TRIGGER trace_cost_write ON collection_runs").update();
    jdbc.sql("DROP FUNCTION trace_cost_write()").update();
    jdbc.sql("DROP TABLE cost_write_trace").update();
  }

  public CollectionRun seedRun() {
    return stores.runs().get(new RunId("seed-run")).orElseThrow();
  }

  public void startCostRun() {
    costClaim =
        stores
            .claims()
            .tryClaim(SOURCE, new OwnerId("cost-worker"), TTL, clock.instant())
            .orElseThrow();
    stores.runs().start(RUN, SOURCE, clock.instant());
  }

  public CollectionRun costRun() {
    return stores.runs().get(RUN).orElseThrow();
  }

  public void recordCost(AccessCost cost) {
    new AccessCompletion(stores, clock)
        .recordCost(
            costClaim,
            RUN,
            stores.sources().orElseThrow().get(SOURCE).orElseThrow().descriptor(),
            cost);
  }

  public void storeCost(AccessCost cost) {
    stores
        .tx()
        .executeWithoutResult(status -> stores.runs().recordAccessCost(RUN, cost, clock.instant()));
  }

  public void givenRunCost(AccessCost cost) {
    storeCost(cost);
    if (costRun().accessCost().isEmpty()) throw new IllegalStateException("given 준비 실패: 기존 비용 없음");
  }

  public void finishCostRun() {
    stores
        .runs()
        .finish(
            RUN,
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            clock.instant(),
            0,
            AccessResult.COMPLETE,
            Optional.of(clock.instant()));
  }

  public void rollbackCost(AccessCost cost) {
    stores
        .tx()
        .executeWithoutResult(
            status -> {
              stores.runs().recordAccessCost(RUN, cost, clock.instant());
              throw new IllegalStateException("injected cost transaction failure");
            });
  }

  public void costStorageFails() {
    rejectCost = true;
    if (jdbc == null) {
      ((MemoryRunStore) stores.runs()).failAccessCostWrites();
    } else {
      jdbc.sql(
              "ALTER TABLE collection_runs ADD CONSTRAINT reject_access_cost CHECK (access_target_count IS NULL)")
          .update();
    }
  }

  private AccessRunScenario(CommitContext.Memory memory) {
    stores = memory.ports().stores();
    jdbc = null;
    handoffs = TransactionOperations.withoutTransaction();
    identities = memory.runs().identities();
    runtime =
        () ->
            MemoryCollectionRuntime.over(
                    clock, memory.sources(), heartbeatClaims(memory.claims()), memory.runs())
                .heartbeats(scheduler)
                .metrics(CollectionMetrics.of(registry))
                .rawItems(memory.rawItems())
                .outbox(memory.outbox())
                .accessObservations(observations())
                .adapter(adapter)
                .collectAccess(collectAccess)
                .runIds(() -> RUN)
                .build();
    new MemoryIdentitySeed((MemoryIdentityStore) identities)
        .seed(
            new AccessDirectory(
                List.of(new AccessDirectory.User("local-user", "local@example.com", true)),
                List.of(),
                List.of(new AccessDirectory.Link(SOURCE, "U1", "local-user", "admin")),
                List.of(),
                Set.of()));
    seed();
    requireInitialGroupKey();
  }

  private AccessRunScenario(DataSource ds) {
    var ports = CommitContext.Ports.postgres(ds);
    stores = ports.stores();
    jdbc = JdbcClient.create(ds);
    var independent = new TransactionTemplate(new DataSourceTransactionManager(ds));
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    handoffs = independent;
    identities = new PostgresIdentityStore(jdbc);
    runtime =
        () ->
            PostgresCollectionRuntime.over(ds, clock)
                .claims(heartbeatClaims(stores.claims()))
                .heartbeats(scheduler)
                .metrics(CollectionMetrics.of(registry))
                .adapter(adapter)
                .collectAccess(collectAccess)
                .runIds(() -> RUN)
                .accessObservations(observations())
                .build();
    seed();
    jdbc.sql("INSERT INTO users VALUES ('local-user', 'local@example.com', true, 'member', :at)")
        .param("at", Timestamp.from(T0))
        .update();
    jdbc.sql(
            "INSERT INTO user_source_identities VALUES (:source, 'U1', 'local-user', 'admin', :at, 'local-user')")
        .param("source", SOURCE.value())
        .param("at", Timestamp.from(T0))
        .update();
    requireInitialGroupKey();
  }

  public static AccessRunScenario memory() {
    return new AccessRunScenario(CommitContext.Memory.create());
  }

  public static AccessRunScenario postgres(DataSource ds) {
    return new AccessRunScenario(ds);
  }

  private void requireInitialGroupKey() {
    if (!keys().contains("group:access-source:C1"))
      throw new IllegalStateException("given 준비 실패: U1은 기존 C1 그룹 키를 가져야 한다");
  }

  private void seed() {
    stores
        .sources()
        .orElseThrow()
        .save(SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", T0));
    RunId seed = new RunId("seed-run");
    stores.runs().start(seed, SOURCE, T0);
    stores
        .accessObservations()
        .stage(
            seed,
            new AclPage(
                Map.of(),
                List.of(new GroupSnapshot("C1", Set.of("U1", "U2"), T0)),
                List.of(identity("U1", T0), identity("U2", T0))));
    stores
        .accessObservations()
        .merge(seed, SOURCE, AccessMerge.COMPLETE, IdentityCoverage.PARTIAL, T0, () -> {});
    stores.accessObservations().discard(seed);
    stores
        .runs()
        .finish(
            seed,
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            T0,
            0,
            AccessResult.COMPLETE,
            Optional.of(T0));
    stores.rawItems().apply(SOURCE, RawItems.item("file:F1").resourceType("gdrive.file").upsert());
    stores
        .rawAcls()
        .observe(
            SOURCE,
            "file:F1",
            RawAcl.observed(
                Status.OK, Set.of(AccessKey.parse("domain:access-source:example.com")), T0),
            T0);
  }

  private WorkClaimPort heartbeatClaims(WorkClaimPort delegate) {
    return new WorkClaimPort() {
      private int checks;

      @Override
      public Optional<WorkClaim> tryClaim(
          SourceInstanceId source, OwnerId owner, Duration ttl, Instant at) {
        return delegate.tryClaim(source, owner, ttl, at);
      }

      @Override
      public WorkClaim renew(WorkClaim claim, Duration ttl, Instant at) {
        if (failRenew) throw new IllegalStateException("heartbeat storage unavailable");
        return delegate.renew(claim, ttl, at);
      }

      @Override
      public void requireActive(WorkClaim claim, Instant at) {
        if (++checks > MAX_CLAIM_CHECKS_PER_RUN)
          throw new AssertionError("수집 run의 페이지 순회가 종료되지 않았다");
        delegate.requireActive(claim, at);
        afterClaimCheck.run();
      }

      @Override
      public void release(WorkClaim claim, Instant at) {
        delegate.release(claim, at);
      }

      @Override
      public Optional<WorkClaim> current(SourceInstanceId source) {
        return delegate.current(source);
      }
    };
  }

  public void heartbeatFailsAtAccessReturn() {
    afterAccess =
        () ->
            afterClaimCheck =
                () -> {
                  failRenew = true;
                  scheduler.tick();
                  afterClaimCheck = () -> {};
                };
  }

  public void claimLostAtAccessReturn() {
    if (jdbc != null) {
      afterAccess = this::loseClaimAndTick;
      return;
    }
    afterAccess =
        () ->
            afterClaimCheck =
                () -> {
                  takeover();
                  scheduler.tick();
                  afterClaimCheck = () -> {};
                };
  }

  public void claimLostAtBodyReturn() {
    afterBody = this::loseClaimAndTick;
  }

  private void loseClaimAndTick() {
    takeover();
    scheduler.tick();
  }

  public void heartbeatFailsDuringAccess() {
    afterAccess =
        () -> {
          failRenew = true;
          scheduler.tick();
        };
  }

  public void unsupportedWithFailedHeartbeat() {
    unsupported = true;
    heartbeatFailsAfterBody();
  }

  public void heartbeatFailsAfterBody() {
    afterBody = this::failHeartbeat;
  }

  public void bodyPageAfterFailedHeartbeat() {
    bodyPage = true;
    beforeBodyPage = this::failHeartbeat;
  }

  private void failHeartbeat() {
    failRenew = true;
    scheduler.tick();
  }

  public void bodyPageWithRejection() {
    bodyPage = true;
    bodyRejections =
        List.of(
            new RejectedItem(
                RejectedItem.FailureKind.INVALID_ITEM,
                "injected invalid provider item",
                Optional.empty(),
                T1,
                Optional.empty()));
  }

  public void rejectConfiguration() {
    invalidConfiguration = true;
  }

  public void collectMissingSource() {
    runtime.get().collect(new SourceInstanceId("missing"), CollectionMode.INCREMENTAL, cancel);
  }

  public void sourceWithoutAdapter() {
    stores
        .sources()
        .orElseThrow()
        .save(SourceInstances.of(SOURCE, "missing-adapter", "{}", "env:FAKE", T0));
  }

  public Optional<WorkClaim> claimBySuccessor() {
    return stores.claims().tryClaim(SOURCE, new OwnerId("successor"), TTL, clock.instant());
  }

  public long liveHeartbeats() {
    return scheduler.liveTasks();
  }

  public void unsupportedWithChangedConfiguration() {
    unsupported = true;
    afterBody = this::changeConfiguration;
  }

  private AccessObservationStore observations() {
    var delegate = stores.accessObservations();
    return new AccessObservationStore() {
      @Override
      public void stage(RunId run, AclPage page) {
        delegate.stage(run, page);
        afterStagedPage.run();
      }

      @Override
      public AccessMerge merge(
          RunId run,
          SourceInstanceId source,
          AccessMerge mode,
          IdentityCoverage identityCoverage,
          Instant at,
          Runnable guard) {
        beforeMerge.run();
        int[] attempts = {0};
        AccessMerge merged =
            delegate.merge(
                run,
                source,
                mode,
                identityCoverage,
                at,
                () -> {
                  if (++attempts[0] == 2) beforeFallback.run();
                  guard.run();
                });
        afterMerge.run();
        return merged;
      }

      @Override
      public void discard(RunId run) {
        if (completionFailures-- > 0)
          throw new CannotGetJdbcConnectionException("completion storage unavailable");
        delegate.discard(run);
      }
    };
  }

  public void bodyFails() {
    afterBody =
        () -> {
          throw new IllegalStateException("body unavailable");
        };
  }

  public void completionStorageFails(int attempts) {
    completionFailures = attempts;
  }

  public double failedRunCount() {
    return registry.find(CollectionMetrics.RUNS).tag("status", "failed").counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  @Override
  public void close() {
    if (rejectCost && jdbc != null)
      jdbc.sql("ALTER TABLE collection_runs DROP CONSTRAINT reject_access_cost").update();
    if (driveServer != null) {
      driveServer.assertNoUnmatched();
      driveServer.close();
    }
    registry.close();
  }

  public void bodyPartial() {
    bodyPage = true;
    bodyFails();
  }

  public void cancelled() {
    afterBody =
        () -> {
          cancel.cancel();
          throw new CallFailureException(
              "body.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
        };
  }

  public void cancelAfterSuccessfulBody() {
    afterBody = cancel::cancel;
  }

  public void clockMovesBackDuringAccess() {
    afterAccess = () -> clock.advance(Duration.ofSeconds(-2));
  }

  public void cancellationDuringAccess() {
    afterAccess =
        () -> {
          throw new CallFailureException(
              "access.collect",
              CallFailure.of(CallFailure.Kind.CANCELLED, "injected access cancellation"));
        };
  }

  public void incompleteAccess() {
    afterAccess =
        () -> {
          throw new IllegalStateException("directory unavailable");
        };
  }

  public void fullIdentityRoster() {
    identityCoverage = IdentityCoverage.FULL;
  }

  public void disabled() {
    collectAccess = false;
  }

  public void takeoverAfterMerge() {
    afterMerge = this::takeover;
  }

  public void takeoverBeforeMerge() {
    beforeMerge = this::takeover;
  }

  public void takeoverBeforeFallback() {
    beforeFallback = this::takeover;
  }

  public void mergeFails() {
    beforeMerge =
        () -> {
          throw new IllegalStateException("merge unavailable");
        };
  }

  public void configurationChangesAfterAccess() {
    afterAccess = this::changeConfiguration;
  }

  private void changeConfiguration() {
    var source = stores.sources().orElseThrow().get(SOURCE).orElseThrow();
    stores
        .sources()
        .orElseThrow()
        .save(
            new SourceInstance(
                source.id(),
                source.kind(),
                source.displayName(),
                new SourceConfig(1, "{\"generation\":\"changed\"}"),
                source.credentialRef(),
                source.enabled(),
                source.schedule(),
                source.shrinkGuard(),
                source.createdAt(),
                T1));
  }

  public void takeoverAfterBody() {
    afterBody = this::takeover;
  }

  public void snapshot() {
    coverage = CollectionCoverage.COMPLETE_SNAPSHOT;
    bodyPage = true;
  }

  public void bodySucceedsWithPage() {
    bodyPage = true;
  }

  public void directory(String... members) {
    pages.add(
        new AclPage(
            Map.of(),
            List.of(new GroupSnapshot("C1", Set.of(members), T1)),
            Arrays.stream(members).map(id -> identity(id, T1)).toList()));
  }

  public void missingTargetAcl() {
    omittedAcls = Set.of("file:F1");
  }

  public void missingTargets(int count) {
    targetIds = IntStream.range(0, count).mapToObj(i -> "file:F" + i).toList();
    for (String id : targetIds)
      stores.rawItems().apply(SOURCE, RawItems.item(id).resourceType("gdrive.file").upsert());
    omittedAcls = Set.copyOf(targetIds);
  }

  public void cancelAfterFirstMissingBatch() {
    afterAccess = () -> afterStagedPage = cancel::cancel;
  }

  public long failedTargetCount() {
    return stores.rawAcls().get(SOURCE, targetIds).values().stream()
        .filter(acl -> acl.status() == Status.FAILED)
        .count();
  }

  public void futureTargetAcl() {
    var future = T1.plusSeconds(10);
    stores
        .rawAcls()
        .observe(
            SOURCE,
            "file:F1",
            RawAcl.observed(
                Status.OK, Set.of(AccessKey.parse("domain:access-source:example.com")), future),
            future);
  }

  public void unobservedTargetAcl() {
    pages.add(new AclPage(Map.of("file:F1", RawAcl.notObserved()), List.of(), List.of()));
  }

  public void partlyObservedTargets() {
    stores.rawItems().apply(SOURCE, RawItems.item("file:F2").resourceType("gdrive.file").upsert());
    stores.rawAcls().observe(SOURCE, "file:F2", RawAcl.observed(Status.OK, Set.of(), T0), T0);
    omittedAcls = Set.of("file:F2");
    pages.add(
        new AclPage(
            Map.of(
                "file:F1",
                RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:access-source:U1")), T1)),
            List.of(),
            List.of()));
  }

  public void missingIdentity() {
    pages.add(
        new AclPage(
            Map.of(),
            List.of(new GroupSnapshot("C1", Set.of("U2", "U3"), T1)),
            List.of(identity("U2", T1))));
  }

  public void invalidGroupReference() {
    directory("U2");
    pages.add(
        new AclPage(
            Map.of(),
            List.of(GroupSnapshot.synthetic("~invalid", Set.of("~other"), T1)),
            List.of()));
  }

  public void conflictingRestriction() {
    stores.rawItems().apply(SOURCE, RawItems.item("file:F2").resourceType("gdrive.file").upsert());
    pages.add(new AclPage(Map.of(), List.of(), List.of(identity("U7", T1))));
    pages.add(
        new AclPage(
            Map.of("file:F1", RawAcl.observed(Status.OK, Set.of(), T1)),
            List.of(),
            List.of(new SourceIdentityRecord("U7", Optional.empty(), false, false, false, T1))));
    pages.add(
        new AclPage(
            Map.of("file:F2", RawAcl.observed(Status.FAILED, Set.of(), T1)), List.of(), List.of()));
  }

  public CollectionRun collect() {
    runtime.get().collect(SOURCE, CollectionMode.INCREMENTAL, cancel);
    return stores.runs().get(RUN).orElseThrow();
  }

  public void commitAclAfterTakeover() {
    WorkClaim claim =
        stores
            .claims()
            .tryClaim(SOURCE, new OwnerId("old-worker"), TTL, clock.instant())
            .orElseThrow();
    stores.runs().start(RUN, SOURCE, T1);
    takeover();
    var commit = new AclCommit(stores, SourceAdapters.of(List.of(adapter)), clock, true);
    commit.commit(
        claim,
        RUN,
        stores.sources().orElseThrow().get(SOURCE).orElseThrow().descriptor(),
        new AclPage(
            Map.of("file:F1", RawAcl.observed(Status.OK, Set.of(), T1)),
            List.of(new GroupSnapshot("C1", Set.of("U2"), T1)),
            List.of(identity("U2", T1))));
  }

  public void takeover() {
    clock.advance(TTL.plusSeconds(1));
    handoffs.executeWithoutResult(
        status -> {
          var unused =
              stores
                  .claims()
                  .tryClaim(SOURCE, new OwnerId("successor"), TTL, clock.instant())
                  .orElseThrow();
        });
  }

  public int accessCalls() {
    return accessCalls;
  }

  public Set<String> keys() {
    return identities
        .accessKeysOf("local-user", new DomainPolicy(Set.of(), Map.of()))
        .orElseThrow()
        .stream()
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  public List<String> members() {
    if (jdbc == null)
      return ((MemoryAccessObservationStore) stores.accessObservations())
          .snapshot(SOURCE).groups().get("C1").members().stream().sorted().toList();
    return Arrays.asList(
        jdbc.sql(
                "SELECT members FROM source_groups WHERE source_instance_id = :source AND group_id = 'C1'")
            .param("source", SOURCE.value())
            .query((rs, row) -> (String[]) rs.getArray(1).getArray())
            .single());
  }

  public Map<String, RawAcl.Observed> acls() {
    return stores.rawAcls().get(SOURCE, List.of("file:F1", "file:F2"));
  }

  public long outboxVersion() {
    return stores.outbox().request(SOURCE).map(OutboxPort.Request::version).orElse(0L);
  }

  public long changes() {
    return stores.changes().head();
  }

  public int staged() {
    if (jdbc == null) {
      var staged =
          ((MemoryAccessObservationStore) stores.accessObservations()).staged(RUN).snapshot();
      return staged.groups().size() + staged.identities().size();
    }
    return jdbc.sql("SELECT count(*) FROM access_observations WHERE run_id = 'access-run'")
        .query(Integer.class)
        .single();
  }

  public int running() {
    return jdbc.sql("SELECT count(*) FROM collection_runs WHERE status = 'running'")
        .query(Integer.class)
        .single();
  }

  public String currentOwner() {
    return stores.claims().current(SOURCE).orElseThrow().owner().value();
  }

  public long activeItems() {
    return stores.rawItems().count(SOURCE);
  }

  public void rejectGroups() {
    jdbc.sql(
            "ALTER TABLE source_groups ADD CONSTRAINT reject_access_merge CHECK (group_id <> 'C1') NOT VALID")
        .update();
  }

  public void allowGroups() {
    jdbc.sql("ALTER TABLE source_groups DROP CONSTRAINT reject_access_merge").update();
  }

  public boolean hasIdentity(String id) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM source_identities WHERE source_instance_id = :source AND source_user_id = :id)")
        .param("source", SOURCE.value())
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  public void traceWrites() {
    jdbc.sql("CREATE TABLE access_completion_trace (seq bigserial, xid bigint, event text)")
        .update();
    jdbc.sql(
            """
        CREATE FUNCTION trace_access_completion() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          INSERT INTO access_completion_trace(xid, event) VALUES
            (txid_current(), CASE WHEN TG_TABLE_NAME = 'raw_changes' THEN 'changes.appendAll' ELSE TG_TABLE_NAME END);
          RETURN NULL;
        END $$
        """)
        .update();
    for (String table : tracedTables())
      jdbc.sql(
              "CREATE TRIGGER trace_access_completion AFTER INSERT OR UPDATE OR DELETE ON "
                  + table
                  + " FOR EACH STATEMENT EXECUTE FUNCTION trace_access_completion()")
          .update();
  }

  private List<String> tracedTables() {
    return jdbc.sql(
            "SELECT quote_ident(tablename) FROM pg_tables WHERE schemaname = 'public' AND tablename NOT IN ('access_completion_trace', 'flyway_schema_history')")
        .query(String.class)
        .list();
  }

  public String lastCompletionWrite() {
    return jdbc.sql(
            """
        SELECT event FROM access_completion_trace WHERE xid =
          (SELECT xid FROM access_completion_trace WHERE event = 'changes.appendAll' ORDER BY seq DESC LIMIT 1)
        ORDER BY seq DESC LIMIT 1
        """)
        .query(String.class)
        .single();
  }

  public void stopTracingWrites() {
    for (String table : tracedTables())
      jdbc.sql("DROP TRIGGER trace_access_completion ON " + table).update();
    jdbc.sql("DROP FUNCTION trace_access_completion()").update();
    jdbc.sql("DROP TABLE access_completion_trace").update();
  }

  private static SourceIdentityRecord identity(String id, Instant at) {
    return new SourceIdentityRecord(
        id, Optional.of(id.toLowerCase(Locale.ROOT) + "@example.com"), true, false, false, at);
  }

  private final class ScriptedAdapter implements SourceAdapter {
    @Override
    public AccessPolicy accessPolicy() {
      if (remoteAccess != null) return remoteAccess;
      return (unsupported
              ? AccessPolicy.unsupported()
              : AccessPolicy.supported(Set.of("gdrive.file"), Set.of("gdrive.file")))
          .withIdentityCoverage(identityCoverage)
          .collecting(this::collectAccess);
    }

    @Override
    public String kind() {
      return sourceKind;
    }

    @Override
    public void validateConfig(SourceConfig config) {
      if (invalidConfiguration)
        throw new CallFailureException(
            "access.validate",
            CallFailure.of(
                CallFailure.Kind.INVALID_CONFIGURATION, "injected invalid configuration"));
    }

    @Override
    public CollectionCoverage coverage(
        SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
      return coverage;
    }

    @Override
    public void collect(CollectionInput input, PageSink sink) {
      if (bodyPage) {
        beforeBodyPage.run();
        sink.accept(
            new CollectionPage(
                List.of(RawItems.item("file:F2").resourceType("gdrive.file").upsert()),
                bodyRejections,
                new Checkpoint(1, "{\"cursor\":\"next\"}")));
      }
      afterBody.run();
    }

    private void collectAccess(AccessInput input, AclSink sink) {
      accessCalls++;
      pages.forEach(sink::accept);
      completeTargetAcls(input, sink);
      afterAccess.run();
      clock.advance(Duration.ofSeconds(1));
    }

    private void completeTargetAcls(AccessInput input, AclSink sink) {
      Set<String> emitted =
          pages.stream().flatMap(page -> page.acls().keySet().stream()).collect(Collectors.toSet());
      Map<String, RawAcl> remaining =
          input.externalIds().stream()
              .filter(id -> !emitted.contains(id) && !omittedAcls.contains(id))
              .collect(Collectors.toMap(id -> id, id -> RawAcl.observed(Status.OK, Set.of(), T1)));
      if (!remaining.isEmpty()) sink.accept(new AclPage(remaining, List.of(), List.of()));
    }
  }
}
