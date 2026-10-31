package io.scos.collection.internal;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;

/** 수집 run 기록. 카운터는 페이지 커밋이 올리고, 완료가 상태·오류를 쓴다. */
public record CollectionRun(
    RunId id,
    SourceInstanceId source,
    RunStatus status,
    Instant startedAt,
    Optional<Instant> finishedAt,
    int pagesApplied,
    int upserted,
    int deleted,
    int unchanged,
    int rejected,
    int errorCount,
    Optional<String> errorKind,
    Optional<String> errorSummary,
    Optional<CollectionCoverage> coverage) {
  /** 막 시작한 running run(카운터·오류 0). */
  public static CollectionRun started(RunId id, SourceInstanceId source, Instant at) {
    return new CollectionRun(
        id,
        source,
        RunStatus.RUNNING,
        at,
        Optional.empty(),
        0,
        0,
        0,
        0,
        0,
        0,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /** 페이지 하나를 더 반영했다. */
  public CollectionRun withPage(int upserted, int deleted, int unchanged, int rejected) {
    return new CollectionRun(
        id,
        source,
        status,
        startedAt,
        finishedAt,
        pagesApplied + 1,
        this.upserted + upserted,
        this.deleted + deleted,
        this.unchanged + unchanged,
        this.rejected + rejected,
        errorCount,
        errorKind,
        errorSummary,
        coverage);
  }

  /** 이 결과로 마감했다. reconciled는 완료가 tombstone으로 바꾼 수(deleted에 더한다). */
  public CollectionRun finished(
      RunOutcome outcome, CollectionCoverage coverage, Instant at, int reconciled) {
    return new CollectionRun(
        id,
        source,
        outcome.status(),
        startedAt,
        Optional.of(at),
        pagesApplied,
        upserted,
        deleted + reconciled,
        unchanged,
        rejected,
        outcome.errorCount(),
        outcome.errorKind(),
        outcome.errorSummary(),
        Optional.of(coverage));
  }

  /** 같은 소스에 새 run이 시작해 running으로 남았던 이 run을 stale_run으로 마감한다(죽은 프로세스의 run). */
  public CollectionRun supersededAt(Instant at) {
    return new CollectionRun(
        id,
        source,
        RunStatus.FAILED,
        startedAt,
        Optional.of(at),
        pagesApplied,
        upserted,
        deleted,
        unchanged,
        rejected,
        errorCount + 1,
        Optional.of("stale_run"),
        Optional.of("superseded by a new run"),
        coverage);
  }
}
