package io.scos.collection.internal;

import io.scos.engine.support.text.EventLog;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * run 완료 유스케이스(F5, 불변식 6). succeeded만 live 점유를 요구하고, succeeded + complete_snapshot일 때만 이번 run에서
 * 관측되지 않은 active 항목을 tombstone으로 바꾼다(reconcile). 단 그 수가 스냅샷 축소 보호 기준을 넘으면 정리하지 않고 run을 succeeded +
 * errorKind snapshot_shrink_guarded로 마감한다(ADR-0028). 비성공 완료는 점유 없이도 기록된다 — 실패가 점유 오류로 가려지지 않게. 완료 후
 * 관측은 지워진다.
 */
public final class RunCompletion {
  public record Input(
      WorkClaim claim,
      RunId runId,
      RunStatus status,
      CollectionCoverage coverage,
      java.time.Instant finishedAt,
      int errorCount,
      Optional<String> errorKind,
      Optional<String> errorSummary) {
    public Input {
      if (status == RunStatus.RUNNING)
        throw new IllegalArgumentException("completion status must be terminal");
      errorKind = errorKind == null ? Optional.empty() : errorKind;
      errorSummary =
          errorSummary == null
              ? Optional.empty()
              : errorSummary.map(s -> s.length() > 500 ? s.substring(0, 500) : s);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(RunCompletion.class);

  private final TransactionOperations tx;
  private final WorkClaimPort claims;
  private final RunStore runs;
  private final RawItemStore rawItems;
  private final OutboxPort outbox;
  private final SourceChangeLog changes;
  private final SnapshotShrinkGuard shrinkGuard;

  public RunCompletion(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      OutboxPort outbox,
      SourceChangeLog changes,
      SnapshotShrinkGuard shrinkGuard) {
    this.tx = tx;
    this.claims = claims;
    this.runs = runs;
    this.rawItems = rawItems;
    this.outbox = outbox;
    this.changes = changes;
    this.shrinkGuard = shrinkGuard;
  }

  public CollectionRun complete(Input in) {
    SourceInstanceId source = in.claim().source();
    Completed completed = tx.execute(status -> completeInTransaction(in));
    // 커밋 뒤의 보고가 실패해도 run은 이미 마감됐다 — 던지면 호출자가 끝난 run을 실패로 다시 마감하려 한다(로그만 남긴다)
    try {
      completed.verdict().ifPresent(v -> shrinkGuard.report(source, in.runId(), v));
    } catch (RuntimeException e) {
      EventLog.error(log, "collection.snapshot.report_failed")
          .source(source)
          .run(in.runId().value())
          .cause(e)
          .log();
    }
    return completed.run();
  }

  /**
   * complete_snapshot run이 시작할 때 부른다: 정리 판정의 분모(이 run 전부터 있던 active 수)를 남긴다(ADR-0028 §1). 점유를 쥔 뒤라
   * 그사이 다른 쓰기가 없다.
   */
  public void recordSnapshotBaseline(SourceInstanceId source, RunId run) {
    shrinkGuard.recordBaseline(source, run, rawItems.count(source));
  }

  /** 완료 트랜잭션의 결과와, 정리를 판정했다면 그 판정(커밋 뒤 보고용). */
  private record Completed(CollectionRun run, Optional<SnapshotShrinkGuard.Verdict> verdict) {}

  private Completed completeInTransaction(Input in) {
    SourceInstanceId source = in.claim().source();
    if (in.status() == RunStatus.SUCCEEDED) claims.requireActive(in.claim(), in.finishedAt());
    runs.requireRunning(in.runId(), source);
    int reconciled = 0;
    Optional<String> errorKind = in.errorKind();
    Optional<String> errorSummary = in.errorSummary();
    Optional<SnapshotShrinkGuard.Verdict> verdict = Optional.empty();
    List<SourceChangeLog.Entry> changed = new ArrayList<>();
    // succeeded_with_rejections는 제외: 거부된 항목의 하위가 열거되지 않았을 수 있어 reconcile이 잘못 지울 수 있다(보수적, TS와
    // 같음)
    if (in.coverage() == CollectionCoverage.COMPLETE_SNAPSHOT
        && in.status() == RunStatus.SUCCEEDED) {
      Set<String> observed = runs.observations(in.runId());
      // 스냅샷 축소 보호(ADR-0028, TS와 다른 의도한 차이): 너무 많이 지우려 하면 정리하지 않고 run은 succeeded로 끝낸다
      SnapshotShrinkGuard.Verdict judged =
          shrinkGuard.judge(
              source,
              in.runId(),
              rawItems.count(source),
              rawItems.countUnobserved(source, observed),
              in.finishedAt());
      verdict = Optional.of(judged);
      if (judged instanceof SnapshotShrinkGuard.Verdict.Guarded guarded) {
        errorKind = Optional.of(SnapshotShrinkGuard.ERROR_KIND);
        errorSummary = Optional.of(guarded.summary());
      } else {
        List<String> absent = rawItems.tombstoneUnobserved(source, observed, in.finishedAt());
        for (String id : absent)
          changed.add(
              new SourceChangeLog.Entry(
                  source,
                  Optional.of(id),
                  SourceChangeLog.Kind.SNAPSHOT_ABSENT,
                  Optional.empty(),
                  Optional.empty(),
                  in.finishedAt()));
        reconciled = absent.size();
        runs.recordSnapshotCompletion(source, in.finishedAt(), reconciled);
        if (reconciled > 0) outbox.requestProjection(source, in.finishedAt());
      }
    }
    // F10: succeeded가 아닌 완료는 collection_incomplete(TS와 같이 succeeded_with_rejections 포함)
    if (in.status() != RunStatus.SUCCEEDED)
      changed.add(
          new SourceChangeLog.Entry(
              source,
              Optional.empty(),
              SourceChangeLog.Kind.COLLECTION_INCOMPLETE,
              Optional.empty(),
              Optional.of(
                  "{\"status\":\""
                      + in.status().wire()
                      + "\",\"errorKind\":"
                      + in.errorKind().map(k -> "\"" + k + "\"").orElse("null")
                      + "}"),
              in.finishedAt()));
    CollectionRun finished =
        runs.finish(
            in.runId(),
            in.status(),
            in.coverage(),
            in.finishedAt(),
            in.errorCount(),
            errorKind,
            errorSummary,
            reconciled);
    changes.appendAll(changed); // 마지막 작업 — 변경 로그 쓰기 잠금은 여기서 커밋까지만(ADR-0021)
    return new Completed(finished, verdict);
  }
}
