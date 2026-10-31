package io.scos.collection.internal;

import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceDescriptor;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 페이지 커밋 유스케이스(F2, 불변식 2·5). 한 트랜잭션 안에서 점유 검사 → run running·소스 일치 → mutation 적용 → 관측 기록 → rejection
 * 적재 → 체크포인트 revision+1 → run 카운터 → 투영 요청(변경이 있을 때만). 어느 검사든 실패하면 페이지 전체가 롤백된다. 이 클래스는 포트와 트랜잭션 경계만
 * 안다({@link CollectionStores}).
 */
public final class PageCommit {
  private final TransactionOperations tx;
  private final WorkClaimPort claims;
  private final RunStore runs;
  private final RawItemStore rawItems;
  private final CheckpointPort checkpoints;
  private final OutboxPort outbox;
  private final SourceChangeLog changes;
  private final Clock clock;
  private final CollectionMetrics metrics;
  private final Progress progress;
  private final Optional<BodyCompletion> bodies;
  private final Optional<ReconciliationDiscovery> reconciliation;
  private final Optional<SourceInstanceStore> sources;

  public PageCommit(
      CollectionStores stores, Clock clock, CollectionMetrics metrics, Progress progress) {
    this.tx = stores.tx();
    this.sources = stores.sources();
    this.claims = stores.claims();
    this.runs = stores.runs();
    this.rawItems = stores.rawItems();
    this.checkpoints = stores.checkpoints();
    this.outbox = stores.outbox();
    this.changes = stores.changes();
    this.clock = clock;
    this.metrics = metrics;
    this.progress = progress;
    this.reconciliation =
        stores.bodies().map(store -> new ReconciliationDiscovery(store, rawItems));
    this.bodies =
        stores
            .bodies()
            .map(store -> new BodyCompletion(store, rawItems, SnapshotShrinkLimits.DEFAULTS));
  }

  public PageCommitResult commit(
      WorkClaim claim, RunId runId, long expectedRevision, CollectionPage page) {
    return commit(claim, runId, expectedRevision, page, Optional.empty());
  }

  public PageCommitResult commit(
      WorkClaim claim,
      RunId runId,
      long expectedRevision,
      CollectionPage page,
      SourceDescriptor expectedSource) {
    return commit(claim, runId, expectedRevision, page, Optional.of(expectedSource));
  }

  private PageCommitResult commit(
      WorkClaim claim,
      RunId runId,
      long expectedRevision,
      CollectionPage page,
      Optional<SourceDescriptor> expectedSource) {
    PageCommitResult committed =
        metrics
            .pageCommit()
            .record(
                () -> commitInTransaction(claim, runId, expectedRevision, page, expectedSource));
    progress.mark(claim.source());
    return committed;
  }

  private PageCommitResult commitInTransaction(
      WorkClaim claim,
      RunId runId,
      long expectedRevision,
      CollectionPage page,
      Optional<SourceDescriptor> expectedSource) {
    return tx.execute(
        status -> {
          var now = clock.instant();
          claims.requireActive(claim, now);
          runs.requireRunning(runId, claim.source());
          requireConfiguration(claim, expectedSource);
          var resourceComparisons =
              reconciliation
                  .map(discovery -> discovery.beforeApply(claim.source(), runId, page))
                  .orElseGet(List::of);
          var tally = new PageTally(claim.source(), now, page.mutations().size());
          for (RawMutation m : page.mutations()) tally.record(m, rawItems.apply(claim.source(), m));
          if (!page.bodies().isEmpty())
            tally.recordAbsences(
                bodies
                    .orElseThrow(
                        () -> new IllegalStateException("Body completion store is not configured"))
                    .apply(claim.source(), runId, page, now));
          reconciliation.ifPresent(
              discovery -> discovery.resourcesApplied(claim.source(), runId, resourceComparisons));
          runs.observe(runId, tally.observed());
          runs.addRejections(runId, claim.source(), now, page.rejections());
          page.absenceVerification()
              .ifPresent(
                  verified ->
                      runs.recordAbsenceValidation(
                          runId,
                          new RunStore.AbsenceValidation(
                              verified.fingerprint(),
                              verified.candidateToken(),
                              verified.exclusions())));
          long revision =
              checkpoints.advance(claim.source(), expectedRevision, page.nextCheckpoint(), now);
          PageCommitResult result = tally.result(revision, page.rejections().size());
          runs.addPage(
              runId, result.upserted(), result.deleted(), result.unchanged(), result.rejected());
          // 변경이 없는 페이지는 투영을 요청하지 않는다(backoff를 풀지 않는다)
          if (tally.changedAny()) outbox.requestProjection(claim.source(), now);
          changes.appendAll(tally.changes()); // 마지막 작업 — 변경 로그 쓰기 잠금은 여기서 커밋까지만(ADR-0021)
          return result;
        });
  }

  private void requireConfiguration(WorkClaim claim, Optional<SourceDescriptor> expectedSource) {
    expectedSource.ifPresent(
        expected -> {
          if (!claim.source().equals(expected.id()))
            throw new SourceConfigurationChangedException();
          sources
              .orElseThrow(
                  () -> new IllegalStateException("Source configuration store is not configured"))
              .requireCollectionConfiguration(expected);
        });
  }
}
