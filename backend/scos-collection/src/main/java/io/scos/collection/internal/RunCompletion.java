package io.scos.collection.internal;

import io.scos.engine.EventLog;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionState;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
      RunOutcome outcome,
      CollectionCoverage coverage,
      Instant finishedAt,
      Optional<SourceDescriptor> expectedSource,
      Optional<SnapshotScope> expectedVerification) {
    public Input(
        WorkClaim claim,
        RunId runId,
        RunOutcome outcome,
        CollectionCoverage coverage,
        Instant finishedAt,
        Optional<SourceDescriptor> expectedSource) {
      this(claim, runId, outcome, coverage, finishedAt, expectedSource, Optional.empty());
    }

    public Input(
        WorkClaim claim,
        RunId runId,
        RunOutcome outcome,
        CollectionCoverage coverage,
        Instant finishedAt) {
      this(claim, runId, outcome, coverage, finishedAt, Optional.empty(), Optional.empty());
    }

    RunStatus status() {
      return outcome.status();
    }
  }

  public static final String ABSENCE_DEFERRED = "absence_verification_deferred";
  private static final Logger log = LoggerFactory.getLogger(RunCompletion.class);

  private final TransactionOperations tx;
  private final WorkClaimPort claims;
  private final RunStore runs;
  private final RawItemStore rawItems;
  private final OutboxPort outbox;
  private final SourceChangeLog changes;
  private final SnapshotShrinkGuard shrinkGuard;
  private final Optional<SourceInstanceStore> sources;
  private final Optional<BodyCompletion> bodies;
  private final SnapshotAbsences absences;
  private final Optional<ReconciliationDiscovery> reconciliation;

  public RunCompletion(CollectionStores stores, SnapshotShrinkGuard shrinkGuard) {
    this.tx = stores.tx();
    this.sources = stores.sources();
    this.claims = stores.claims();
    this.runs = stores.runs();
    this.rawItems = stores.rawItems();
    this.outbox = stores.outbox();
    this.changes = stores.changes();
    this.shrinkGuard = shrinkGuard;
    this.bodies =
        stores
            .bodies()
            .map(store -> new BodyCompletion(store, rawItems, SnapshotShrinkLimits.DEFAULTS));
    this.absences = new SnapshotAbsences(rawItems, runs, stores.bodies());
    this.reconciliation =
        stores.bodies().map(store -> new ReconciliationDiscovery(store, rawItems));
  }

  public CollectionState state(SourceInstanceId source, RunId run, Optional<SnapshotScope> scope) {
    CollectionState bodyState =
        bodies.map(body -> body.state(source)).orElseGet(CollectionState::unavailable);
    return new CollectionState() {
      @Override
      public Map<String, BodySummary> bodies(
          String fingerprint, List<String> ids, CancellationToken cancel) {
        return bodyState.bodies(fingerprint, ids, cancel);
      }

      @Override
      public boolean reconciliationBaseline(String fingerprint, CancellationToken cancel) {
        bodyState.reconciliationBaseline(fingerprint, cancel);
        return runs.hasReconciliationBaseline(source, fingerprint);
      }

      @Override
      public AbsenceCandidates absences(CancellationToken cancel) {
        return scope
            .map(s -> RunCompletion.this.absences.candidates(source, run, s, cancel))
            .orElseGet(() -> bodyState.absences(cancel));
      }
    };
  }

  public Optional<ReconciliationDiscovery.Summary> reconciliation(CollectionRun run) {
    return reconciliation.flatMap(discovery -> discovery.report(run));
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
    if (in.status() == RunStatus.SUCCEEDED)
      in.expectedSource()
          .ifPresent(
              expected -> {
                if (!source.equals(expected.id())) throw new SourceConfigurationChangedException();
                sources
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Source configuration store is not configured"))
                    .requireCollectionConfiguration(expected);
              });
    SnapshotReconcile reconciled =
        reconcilesSnapshot(in) ? reconcile(in) : SnapshotReconcile.skipped(in.outcome());
    List<SourceChangeLog.Entry> changed = new ArrayList<>(reconciled.changes());
    // F10: succeeded가 아닌 완료는 collection_incomplete(succeeded_with_rejections 포함)
    if (in.status() != RunStatus.SUCCEEDED)
      changed.add(
          SourceChangeLog.Entry.collectionIncomplete(
              source, in.status(), in.outcome().errorKind(), in.finishedAt()));
    CollectionRun finished =
        runs.finish(
            in.runId(), reconciled.outcome(), in.coverage(), in.finishedAt(), reconciled.count());
    changes.appendAll(changed); // 마지막 작업 — 변경 로그 쓰기 잠금은 여기서 커밋까지만(ADR-0021)
    return new Completed(finished, reconciled.verdict());
  }

  /**
   * succeeded + complete_snapshot만 정리한다. succeeded_with_rejections는 제외: 거부된 항목의 하위가 열거되지 않았을 수 있어
   * reconcile이 잘못 지울 수 있다(보수적).
   */
  private static boolean reconcilesSnapshot(Input in) {
    return in.coverage() == CollectionCoverage.COMPLETE_SNAPSHOT
        && in.status() == RunStatus.SUCCEEDED;
  }

  /** 정리 단계의 결과: tombstone 수, 판정, 마감할 결과(보호면 표시가 붙는다), 남길 변경. */
  private record SnapshotReconcile(
      int count,
      Optional<SnapshotShrinkGuard.Verdict> verdict,
      RunOutcome outcome,
      List<SourceChangeLog.Entry> changes) {
    static SnapshotReconcile skipped(RunOutcome outcome) {
      return new SnapshotReconcile(0, Optional.empty(), outcome, List.of());
    }
  }

  /**
   * 이번 run이 관측하지 않은 active를 tombstone으로 바꾼다. 스냅샷 축소 보호(ADR-0028): 너무 많이 지우려 하면 정리하지 않고 run은
   * succeeded + snapshot_shrink_guarded로 끝낸다.
   */
  private SnapshotReconcile reconcile(Input in) {
    SourceInstanceId source = in.claim().source();
    Optional<String> deferred = deferredAbsences(in);
    if (deferred.isPresent())
      return SnapshotReconcile.skipped(
          in.outcome()
              .markedAs(
                  ABSENCE_DEFERRED, "complete snapshot reconcile deferred: " + deferred.get()));
    Set<String> observed = runs.observations(in.runId());
    SnapshotShrinkGuard.Verdict judged =
        shrinkGuard.judge(
            source,
            in.runId(),
            rawItems.count(source),
            rawItems.countUnobserved(source, observed),
            in.finishedAt());
    if (judged instanceof SnapshotShrinkGuard.Verdict.Guarded guarded)
      return new SnapshotReconcile(
          0,
          Optional.of(judged),
          in.outcome().markedAs(SnapshotShrinkGuard.ERROR_KIND, guarded.summary()),
          List.of());
    List<String> absent = rawItems.tombstoneUnobserved(source, observed, in.finishedAt());
    in.expectedVerification()
        .ifPresent(
            scope ->
                reconciliation.ifPresent(
                    discovery -> discovery.absencesApplied(source, in.runId(), scope, absent)));
    runs.recordSnapshotCompletion(
        source,
        in.finishedAt(),
        absent.size(),
        in.expectedVerification().map(SnapshotScope::fingerprint));
    if (!absent.isEmpty()) outbox.requestProjection(source, in.finishedAt());
    return new SnapshotReconcile(
        absent.size(),
        Optional.of(judged),
        in.outcome(),
        absent.stream()
            .map(id -> SourceChangeLog.Entry.snapshotAbsent(source, id, in.finishedAt()))
            .toList());
  }

  private Optional<String> deferredAbsences(Input in) {
    if (in.expectedVerification().isEmpty() && runs.absenceRequirement(in.runId()).isEmpty())
      return Optional.empty();
    return in.expectedVerification()
        .map(scope -> absences.verify(in.claim().source(), in.runId(), scope))
        .orElse(Optional.of("missing_verification_scope"));
  }
}
