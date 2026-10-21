package io.scos.collection.internal;

import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 페이지 커밋 유스케이스(F2, 불변식 2·5). 한 트랜잭션 안에서 점유 검사 → run running·소스 일치 → mutation 적용 → 관측 기록 → rejection
 * 적재 → 체크포인트 revision+1 → run 카운터 → 투영 요청(변경이 있을 때만). 어느 검사든 실패하면 페이지 전체가 롤백된다. 이 클래스는 포트와 트랜잭션 경계만
 * 안다 — 원자성은 어댑터가 같은 DataSource를 공유하는 조립의 성질이다(DESIGN-PATTERNS §10).
 */
public final class PageCommit {
  private final TransactionOperations tx;
  private final WorkClaimPort claims;
  private final RunStore runs;
  private final RawItemStore rawItems;
  private final CheckpointPort checkpoints;
  private final OutboxPort outbox;
  private final Clock clock;
  private final CollectionMetrics metrics;
  private final SourceChangeLog changes;
  private final Progress progress;

  public PageCommit(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      CheckpointPort checkpoints,
      OutboxPort outbox,
      Clock clock,
      CollectionMetrics metrics,
      SourceChangeLog changes,
      Progress progress) {
    this.progress = progress;
    this.changes = changes;
    this.metrics = metrics;
    this.tx = tx;
    this.claims = claims;
    this.runs = runs;
    this.rawItems = rawItems;
    this.checkpoints = checkpoints;
    this.outbox = outbox;
    this.clock = clock;
  }

  public PageCommitResult commit(
      WorkClaim claim, RunId runId, long expectedRevision, CollectionPage page) {
    PageCommitResult committed =
        metrics
            .pageCommit()
            .record(() -> commitInTransaction(claim, runId, expectedRevision, page));
    progress.mark(claim.source());
    return committed;
  }

  private PageCommitResult commitInTransaction(
      WorkClaim claim, RunId runId, long expectedRevision, CollectionPage page) {
    return tx.execute(
        status -> {
          var now = clock.instant();
          claims.requireActive(claim, now);
          runs.requireRunning(runId, claim.source());
          int upserted = 0, deleted = 0, unchanged = 0;
          List<String> observed = new ArrayList<>(page.mutations().size());
          List<SourceChangeLog.Entry> changed = new ArrayList<>();
          for (RawMutation m : page.mutations()) {
            switch (rawItems.apply(claim.source(), m)) {
              case UPSERTED -> {
                upserted++;
                changed.add(
                    new SourceChangeLog.Entry(
                        claim.source(),
                        Optional.of(m.externalId()),
                        SourceChangeLog.Kind.UPSERT,
                        Optional.of(((RawMutation.Upsert) m).item().sourceVersion()),
                        Optional.empty(),
                        now));
              }
              case DELETED -> {
                deleted++;
                changed.add(
                    new SourceChangeLog.Entry(
                        claim.source(),
                        Optional.of(m.externalId()),
                        SourceChangeLog.Kind.PROVIDER_DELETE,
                        m instanceof RawMutation.Delete d ? d.sourceVersion() : Optional.empty(),
                        Optional.empty(),
                        now));
              }
              case UNCHANGED -> unchanged++;
            }
            observed.add(m.externalId());
          }
          runs.observe(runId, observed);
          runs.addRejections(runId, claim.source(), now, page.rejections());
          long revision =
              checkpoints.advance(claim.source(), expectedRevision, page.nextCheckpoint(), now);
          runs.addPage(runId, upserted, deleted, unchanged, page.rejections().size());
          // 변경이 없는 페이지는 투영을 요청하지 않는다(backoff를 풀지 않는다, 리뷰 B-I-7)
          if (upserted + deleted > 0) outbox.requestProjection(claim.source(), now);
          changes.appendAll(changed); // 마지막 작업 — 변경 로그 쓰기 잠금은 여기서 커밋까지만(ADR-0021)
          return new PageCommitResult(
              revision, upserted, deleted, unchanged, page.rejections().size());
        });
  }
}
