package io.scos.collection.internal.projection;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** 실제 메모리 포트로 관측·ACL·유닛·점유를 연결하고 외부 렌더/저장 장애만 주입한다. */
final class ProjectionAclMemoryScenario {
  static final String DOC = "C1/day-2025-10-14";
  static final String RAW = "C1:1760400000.000100";
  static final AccessKey READER = AccessKey.parse("group:slack-a:C1");
  private static final String BODY = "회수된 권한은 투영 실패나 임베딩 지연과 관계없이 검색과 읽기에서 차단되어야 합니다.";
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final SourceInstanceId FOREIGN = new SourceInstanceId("slack-b");
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final CancellationToken cancel = CancellationToken.none();
  private final ScriptedClaims claims = new ScriptedClaims();
  final MemoryProjection projection =
      new MemoryProjection(
          new MutableClock(MemoryProjection.T0), new MemorySourceInstanceStore(), claims);
  private final FailingProjector projector = new FailingProjector(new ObjectMapper());
  private final ScriptedEmbedder embedder = new ScriptedEmbedder();
  private SourceInstanceId requested = SOURCE;

  ProjectionAclMemoryScenario() {
    projection.collectingAccess(
        SlackAssembly.adapter("http://127.0.0.1:1", "synthetic", projection.clock));
    projection.projector(projector);
    projection.slackSourceWithOneMessage(SOURCE.value(), BODY);
    projection.rawAcls.observe(
        SOURCE,
        RAW,
        RawAcl.observed(Status.OK, Set.of(READER), MemoryProjection.T0),
        MemoryProjection.T0);
  }

  void givenProjectedDocument() {
    var report = project();
    if (report.outcome() != ProjectionRun.Outcome.SUCCEEDED || acl().status() != Status.OK)
      throw new IllegalStateException("given 준비 실패: 허용 문서 투영 실패 " + report);
    projection.outbox.requestProjection(SOURCE, projection.clock.instant());
    registry.clear();
  }

  void cancelDuringRendering() {
    restrictRaw();
    projector.onNextProject(cancel::cancel);
  }

  private void restrictRaw() {
    projection.rawAcls.observe(
        SOURCE,
        RAW,
        RawAcl.observed(Status.FAILED, Set.of(), MemoryProjection.T0),
        MemoryProjection.T0);
  }

  void omitRestrictingCarrier() {
    String raw = "C1:1760400000.000200";
    projection.rawItems.apply(
        SOURCE,
        RawItems.item(raw)
            .version("1760400000.000200")
            .metadata("{\"channelId\":\"C1\",\"messageTs\":\"1760400000.000200\"}")
            .inlineJson("{\"text\":\"restricted\"}")
            .upsert());
    projection.rawAcls.observe(
        SOURCE,
        raw,
        RawAcl.observed(Status.OK, Set.of(), MemoryProjection.T0),
        MemoryProjection.T0);
    projector.omitCarrier(raw);
  }

  void omitWholeBundle() {
    projector.omitAllBundles();
  }

  void consumeRestrictingCarrier() {
    omitRestrictingCarrier();
    projector.consumeInput();
  }

  void changeRenderedDocumentId() {
    projector.changeDocumentId();
  }

  void changeRenderedSourceId() {
    projector.changeSourceId();
  }

  void renderFails() {
    projector.failDocument(DOC);
  }

  void renderIsEmpty() {
    projector.emptyDocument(DOC);
  }

  void groupingFails() {
    projector.failGrouping();
  }

  void pendingGeneration() {
    projection.meta.set(
        MemoryProjection.SPACE,
        "embedding:version",
        "pending:test:scripted:4:title+aliases+content");
  }

  void renderIsEmptyWithBlockedPrune() {
    renderIsEmpty();
    pendingGeneration();
  }

  void vanishedDocumentWithBlockedPrune() {
    projection.rawItems.tombstoneSelected(SOURCE, Set.of(RAW), projection.clock.instant());
    pendingGeneration();
  }

  void claimExpiresDuringRendering() {
    projector.onNextProject(() -> projection.clock.advance(MemoryProjection.LEASE_TTL));
  }

  void claimIsReplacedDuringRendering() {
    projector.onNextProject(this::replaceClaim);
  }

  private void replaceClaim() {
    var key = new SourceInstanceId("projection:" + requested.value());
    var old = claims.current(key).orElseThrow();
    claims.release(old, projection.clock.instant());
    if (claims
        .tryClaim(
            key, new OwnerId("successor"), MemoryProjection.LEASE_TTL, projection.clock.instant())
        .isEmpty()) throw new IllegalStateException("given 준비 실패: 후속 점유 획득 실패");
  }

  void cancelAndLoseClaimAtGuard() {
    claims.beforeCheck =
        claim -> {
          cancel.cancel();
          claims.release(claim, projection.clock.instant());
        };
  }

  void heartbeatFailsDuringRendering() {
    restrictRaw();
    projector.onNextProject(
        () -> {
          claims.failRenew = true;
          projection.scheduler.tick();
        });
  }

  void writeFailsWithMessage() {
    claims.checkError = Optional.of(new IllegalStateException("injected write failure"));
  }

  void writeFailsWithoutMessage() {
    claims.checkError = Optional.of(new IllegalStateException());
  }

  void projectionAlreadyRunning() {
    if (claims
        .tryClaim(
            new SourceInstanceId("projection:slack-a"),
            new OwnerId("competitor"),
            MemoryProjection.LEASE_TTL,
            projection.clock.instant())
        .isEmpty()) throw new IllegalStateException("given 준비 실패: 경쟁 점유 획득 실패");
  }

  void missingSource() {
    projection.outbox.complete(SOURCE, projection.outbox.request(SOURCE).orElseThrow().version());
    requested = new SourceInstanceId("missing");
    requestFresh();
  }

  void foreignSourceCollision() {
    projection.outbox.complete(SOURCE, projection.outbox.request(SOURCE).orElseThrow().version());
    projection.slackSourceWithOneMessage(FOREIGN.value(), BODY);
    projection.rawAcls.observe(
        FOREIGN,
        RAW,
        RawAcl.observed(
            Status.OK, Set.of(AccessKey.parse("group:slack-b:C1")), MemoryProjection.T0),
        MemoryProjection.T0);
    requested = FOREIGN;
    projection.clock.advance(Duration.ofSeconds(1));
  }

  void withoutAccessCollection() {
    projection.withoutAccessCollection();
  }

  void loseClaimAfterSync() {
    projection.afterNextSync(this::replaceClaim);
  }

  void loseClaimAfterOwnershipReport() {
    projection.afterOwnershipSkipped(this::replaceClaim);
  }

  int ownershipSkippedCount() {
    return projection.sources.ownershipSkipped(requested);
  }

  void cancelAfterFailedSync() {
    changeBody();
    embedder.fail = true;
    projection.afterOwnershipSkipped(cancel::cancel);
  }

  RawAcl.Observed foreignAcl() {
    return projection.rawAcls.get(FOREIGN, List.of(RAW)).get(RAW);
  }

  Optional<OutboxPort.Request> request() {
    return projection.outbox.request(requested);
  }

  void changeBody() {
    projection.rawItems.apply(
        SOURCE,
        RawItems.item(RAW)
            .version("1760400001.000100")
            .metadata("{\"channelId\":\"C1\",\"messageTs\":\"1760400000.000100\"}")
            .inlineJson("{\"text\":\"바뀐 본문도 원천의 접근 권한과 점유를 확인한 다음 새로운 임베딩을 만들고 안전하게 저장해야 합니다.\"}")
            .upsert());
  }

  void repeatedFailures(int count) {
    projection.retryPolicy(
        new ProjectionRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(4), 3));
    renderFails();
    for (int i = 0; i < count; i++) {
      requestFresh();
      if (project().outcome() != ProjectionRun.Outcome.FAILED)
        throw new IllegalStateException("given 준비 실패: 반복 투영 실패가 필요하다");
    }
    requestFresh();
  }

  private void requestFresh() {
    projection.outbox.requestProjection(requested, projection.clock.instant());
  }

  record Timing(double recorded, long enclosing) {}

  Timing measuredProject() {
    long start = System.nanoTime();
    project();
    long enclosing = System.nanoTime() - start;
    double recorded =
        registry
            .find(ProjectionMetrics.RUN)
            .tag("outcome", "succeeded")
            .timer()
            .totalTime(TimeUnit.NANOSECONDS);
    return new Timing(recorded, enclosing);
  }

  ProjectionRun.Report project() {
    return projection.projection(embedder, registry).runDue(cancel).getFirst();
  }

  DocumentAcl acl() {
    return projection.acls.get(MemoryProjection.SPACE, List.of(DOC)).get(DOC);
  }

  private static final class ScriptedClaims extends MemoryWorkClaimPort {
    private boolean failRenew;
    private Optional<RuntimeException> checkError = Optional.empty();
    private Consumer<WorkClaim> beforeCheck = claim -> {};

    @Override
    public synchronized WorkClaim renew(WorkClaim claim, Duration ttl, Instant now) {
      if (failRenew) throw new IllegalStateException("injected heartbeat storage failure");
      return super.renew(claim, ttl, now);
    }

    @Override
    public synchronized void requireActive(WorkClaim claim, Instant now) {
      beforeCheck.accept(claim);
      if (checkError.isPresent()) throw checkError.get();
      super.requireActive(claim, now);
    }
  }
}
