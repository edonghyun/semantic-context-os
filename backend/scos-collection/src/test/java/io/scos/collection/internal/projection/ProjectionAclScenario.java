package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore.Range;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** 원본 관측·실제 DB 투영·제한 읽기를 한 조립으로 연결한다. 외부 공급자를 호출하지 않는다. */
@Tag("db")
final class ProjectionAclScenario implements AutoCloseable {
  static final Instant T0 = Instant.parse("2026-11-03T00:00:00Z");
  static final Instant T1 = Instant.parse("2026-11-03T00:00:01Z");
  static final Instant LAGGING_CARRIER_REVOKED_AT = Instant.parse("2026-11-02T00:00:02Z");
  static final Instant OTHER_CARRIER_OBSERVED_AT = Instant.parse("2026-11-02T00:00:03Z");
  static final Instant LAGGING_CARRIER_OBSERVED_AT = Instant.parse("2026-11-02T00:00:01Z");
  static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);
  static final String DOC = "C1/day-2025-10-14";
  static final String OTHER_DOC = "C2/day-2025-10-14";
  static final String RAW = "C1:1760400000.000100";
  static final String SECOND_RAW = "C1:1760400000.000200";
  static final AccessKey READER = AccessKey.parse("group:slack-a:C1");
  static final AccessKey NEW_READER = AccessKey.parse("user:slack-a:U2");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final SourceInstanceId FOREIGN = new SourceInstanceId("slack-b");
  private SourceInstanceId projectingSource = SOURCE;
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final String BODY = "검색 권한은 원천의 관측값으로 결정하며 회수된 문서는 즉시 검색과 읽기에서 제외합니다.";
  private static final String CHANGED_BODY = "바뀐 본문은 임베딩 단계의 실패와 관계없이 원천의 권한 회수를 유지해야 합니다.";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final DataSource ds = PostgresSupport.dataSource();
  private final PostgresCollectionConfiguration.Adapters stores =
      PostgresCollectionConfiguration.adapters(ds);
  private final AclStore acls = new PostgresIndexConfiguration().newAclStore(ds);
  private final MutableClock clock = new MutableClock(T0);
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final ScriptedEmbedder embedder = new ScriptedEmbedder();
  private final FailingProjector projector = new FailingProjector(JSON);
  private final PostgresProjection projections;
  private List<ContextUnit> priorUnits = List.of();
  private DocumentAcl priorAcl;
  private Map<String, DocumentAcl> firstEmbeddingAcls = Map.of();

  ProjectionAclScenario() {
    PostgresSupport.truncateAll();
    stores.sources().save(SourceInstances.of(SOURCE, "slack", "{}", "env:SYNTHETIC", T0));
    projections =
        PostgresProjection.collectingAccess(
                ds,
                clock,
                embedder,
                SlackAssembly.adapter("http://127.0.0.1:1", "synthetic", clock))
            .projector(projector);
  }

  void givenProjectedDocument() {
    rawMessage(RAW, "C1", BODY);
    observe(RAW, Status.OK, Set.of(READER), T0);
    acls.put(SPACE, List.of(new DocumentAcl(DOC, Status.OK, Set.of(READER), T0)));
    requestProjection();
    var initial = project();
    if (initial.outcome() != ProjectionRun.Outcome.SUCCEEDED)
      throw new IllegalStateException("given 준비 실패: 초기 투영 실패 " + initial);
    if (readableUnits().isEmpty())
      throw new IllegalStateException("given 준비 실패: 기존 문서가 보여야 한다 — " + units());
    priorAcl = acl();
    priorUnits = units();
    embedder.calls.set(0);
  }

  void givenSecondDocument() {
    rawMessage("C2:1760400000.000100", "C2", BODY);
    observe("C2:1760400000.000100", Status.OK, Set.of(AccessKey.parse("group:slack-a:C2")), T0);
  }

  void givenSecondProjectedDocument() {
    givenSecondDocument();
    requestProjection();
    project();
    if (!storedDocuments().contains(OTHER_DOC))
      throw new IllegalStateException("given 준비 실패: 두 번째 문서가 있어야 한다");
  }

  void restrictBothBeforeEmbedding() {
    observe(RAW, Status.FAILED, Set.of(), T1);
    observe("C2:1760400000.000100", Status.FAILED, Set.of(), T1);
    requestProjection();
    embedder.onNextEmbed(() -> firstEmbeddingAcls = acls.get(SPACE, List.of(DOC, OTHER_DOC)));
  }

  Map<String, DocumentAcl> firstEmbeddingAcls() {
    return firstEmbeddingAcls;
  }

  void givenLaggingCarrier() {
    rawMessage(RAW, "C1", BODY);
    rawMessage(SECOND_RAW, "C1", BODY);
    observe(RAW, Status.OK, Set.of(READER), OTHER_CARRIER_OBSERVED_AT);
    observe(SECOND_RAW, Status.OK, Set.of(READER), LAGGING_CARRIER_OBSERVED_AT);
    requestProjection();
    var initial = project();
    if (initial.outcome() != ProjectionRun.Outcome.SUCCEEDED || readableUnits().isEmpty())
      throw new IllegalStateException("given 준비 실패: 두 carrier 문서가 보여야 한다 — " + initial);
    priorAcl = acl();
  }

  private void rawMessage(String externalId, String channel, String text) {
    String ts = externalId.substring(externalId.indexOf(':') + 1);
    String version = text.equals(CHANGED_BODY) ? "1760400001.000100" : ts;
    stores
        .rawItems()
        .apply(
            projectingSource,
            RawItems.item(externalId)
                .observedAt(clock.instant())
                .version(version)
                .metadata(JSON.writeValueAsString(Map.of("channelId", channel, "messageTs", ts)))
                .inlineJson(JSON.writeValueAsString(Map.of("text", text)))
                .upsert());
  }

  private void observe(String externalId, Status status, Set<AccessKey> keys, Instant at) {
    stores.rawAcls().observe(projectingSource, externalId, RawAcl.observed(status, keys, at), at);
  }

  void givenForeignSourceCollision() {
    projectingSource = FOREIGN;
    stores.sources().save(SourceInstances.of(FOREIGN, "slack", "{}", "env:SYNTHETIC", T0));
    rawMessage(RAW, "C1", CHANGED_BODY);
    observe(RAW, Status.OK, Set.of(AccessKey.parse("group:slack-b:C1")), T0);
    requestProjection();
  }

  void rejectCollisionRequest() {
    JdbcClient.create(ds)
        .sql(
            """
        ALTER TABLE projection_requests ADD CONSTRAINT reject_collision_request
        CHECK (source_instance_id <> 'slack-b' OR version < 2)
        """)
        .update();
  }

  void loseClaimAfterSync() {
    projections.afterNextSync(this::replaceProjectionClaim);
  }

  void givenForeignUnitPruneCandidate() {
    JdbcClient.create(ds)
        .sql("UPDATE units SET source_id = 'slack-b' WHERE document_id = :doc")
        .param("doc", DOC)
        .update();
  }

  Set<String> ownershipSkipped() {
    return projections.ownershipSkipped();
  }

  RawAcl.Observed foreignCarrierAcl() {
    return stores.rawAcls().get(FOREIGN, List.of(RAW)).get(RAW);
  }

  void givenCompletedCollision() {
    project();
  }

  void nextCollectionObservesForeignOk() {
    clock.advance(FIRST_BACKOFF);
    observe(RAW, Status.OK, Set.of(AccessKey.parse("group:slack-b:C1")), clock.instant());
    requestProjection();
  }

  void deleteOriginalDocument() {
    stores
        .rawItems()
        .apply(
            projectingSource,
            new RawMutation.Delete(RAW, Optional.empty(), Optional.empty(), Optional.empty(), T1));
    requestProjection();
  }

  void givenAclOnlyOrphan() {
    givenSecondDocument();
    embedderFails();
    requestProjection();
    project();
    if (acls.get(SPACE, List.of(OTHER_DOC)).isEmpty() || storedDocuments().contains(OTHER_DOC))
      throw new IllegalStateException("given 준비 실패: ACL만 저장된 문서가 있어야 한다");
    embedder.fail = false;
    embedder.failOn = null;
  }

  void deleteOrphanOriginal() {
    stores
        .rawItems()
        .apply(
            SOURCE,
            new RawMutation.Delete(
                "C2:1760400000.000100", Optional.empty(), Optional.empty(), Optional.empty(), T1));
    clock.advance(FIRST_BACKOFF);
    requestProjection();
  }

  Map<String, DocumentAcl> orphanAcls() {
    return acls.get(SPACE, List.of(OTHER_DOC));
  }

  void revokeWithEmptyKeys() {
    observe(RAW, Status.OK, Set.of(), T1);
    requestProjection();
  }

  void restrictAcl() {
    observe(RAW, Status.FAILED, Set.of(), T1);
    requestProjection();
  }

  void restrictLaggingCarrier() {
    observe(SECOND_RAW, Status.FAILED, Set.of(), LAGGING_CARRIER_REVOKED_AT);
    requestProjection();
  }

  void changeAclOnly() {
    observe(RAW, Status.OK, Set.of(NEW_READER), T1);
    requestProjection();
  }

  void changeBody() {
    rawMessage(RAW, "C1", CHANGED_BODY);
    requestProjection();
  }

  void embedderFails() {
    embedder.fail = true;
  }

  void projectorFails() {
    projector.failDocument(DOC);
    requestProjection();
  }

  void groupingFails() {
    projector.failGrouping();
    requestProjection();
  }

  void projectorReturnsEmpty() {
    projector.emptyDocument(DOC);
    requestProjection();
  }

  void givenProjectorFailure() {
    projectorFails();
    project();
  }

  void recoverProjectorAtRetry() {
    projector.recover();
    clock.advance(FIRST_BACKOFF);
  }

  void pendingGeneration() {
    PostgresTestAdapters.spaceMeta(ds)
        .set(SPACE, "embedding:version", "pending:test:scripted:4:title+aliases+content");
  }

  void mismatchedGeneration() {
    PostgresTestAdapters.spaceMeta(ds)
        .set(SPACE, "embedding:version", "test:other:4:title+aliases+content");
  }

  void loseClaimBeforeSync() {
    projector.onNextProject(this::replaceProjectionClaim);
  }

  private void replaceProjectionClaim() {
    var key = ProjectionRun.projectionKey(projectingSource);
    var old = stores.claims().current(key).orElseThrow();
    stores.claims().release(old, clock.instant());
    if (stores
        .claims()
        .tryClaim(key, new OwnerId("competitor"), MemoryProjection.LEASE_TTL, clock.instant())
        .isEmpty()) throw new IllegalStateException("given 준비 실패: competitor claim failed");
  }

  void renderFailureAtLaterClock() {
    clock.advance(Duration.ofSeconds(1));
    projectorFails();
  }

  void requestAgainAtLaterClock() {
    clock.advance(Duration.ofSeconds(1));
    requestProjection();
  }

  void advanceSubMicrosecond() {
    clock.advance(Duration.ofNanos(100));
  }

  void advanceClockDuringRendering() {
    projector.onNextProject(() -> clock.advance(Duration.ofSeconds(5)));
  }

  void disableAccessCollection() {
    projections.withoutAccessCollection();
  }

  private void requestProjection() {
    stores.outbox().requestProjection(projectingSource, clock.instant());
  }

  ProjectionRun.Report project() {
    return projections.run("acl-projection", scheduler).runDue(CancellationToken.none()).getFirst();
  }

  DocumentAcl acl() {
    return acls.get(SPACE, List.of(DOC)).get(DOC);
  }

  DocumentAcl priorAcl() {
    return priorAcl;
  }

  Optional<OutboxPort.Request> request() {
    return stores.outbox().request(projectingSource);
  }

  int embedCalls() {
    return embedder.calls.get();
  }

  List<ContextUnit> units() {
    return PostgresTestAdapters.unitMaintenance(ds).listAll(SPACE);
  }

  List<ContextUnit> priorUnits() {
    return priorUnits;
  }

  List<ContextUnit> readableUnits() {
    var access = new AccessScope.Restricted(Principal.singleUser(), Set.of(READER));
    return PostgresTestAdapters.unitStore(ds).listByDocument(SPACE, access, DOC, new Range(0, 20));
  }

  Set<String> storedDocuments() {
    return Set.copyOf(
        PostgresTestAdapters.unitMaintenance(ds).listDocumentIds(SPACE, SOURCE.value()));
  }

  @Override
  public void close() {
    JdbcClient.create(ds)
        .sql("ALTER TABLE projection_requests DROP CONSTRAINT IF EXISTS reject_collision_request")
        .update();
  }
}
