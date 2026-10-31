package io.scos.collection.internal;

import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 감독의 due 정책: 소스마다 다음 자격 시각과 수집 모드를 정한다. 점유가 live면 만료 시각, 마지막 시도가 없으면 즉시, 마지막 run이 성공이
 * 아니면(failed·partially_succeeded·cancelled·running) min(retryMax, retryBase·2^(max(0, 최근 실패 수−1))),
 * 성공이면 interval(소스별 값이 있으면 그것, ADR-0022 §3). 스냅샷 모드는 마지막 complete snapshot 완료·마지막 스냅샷 시도 중 늦은 시각 +
 * 간격(소스별 값 → 데몬 값)이 지났을 때(둘 다 없으면 즉시).
 */
final class DuePolicy {
  private final RunStore runs;
  private final WorkClaimPort claims;
  private final SourceSupervisor.Options options;

  /** 프로세스 메모리에만 — 재기동 시 한 번의 즉시 재시도는 허용한다. */
  private final Map<SourceInstanceId, Instant> lastSnapshotAttemptAt = new ConcurrentHashMap<>();

  DuePolicy(RunStore runs, WorkClaimPort claims, SourceSupervisor.Options options) {
    this.runs = runs;
    this.claims = claims;
    this.options = options;
  }

  /** 다음 자격 시각. 비어 있으면 지금 due. */
  Optional<Instant> nextEligibleAt(SourceInstance instance, Instant now) {
    SourceInstanceId id = instance.id();
    Optional<WorkClaim> claim = claims.current(id).filter(c -> c.isActiveAt(now));
    if (claim.isPresent()) return Optional.of(claim.get().expiresAt());
    Optional<CollectionRun> last = runs.latest(id);
    if (last.isEmpty()) return Optional.empty();
    Duration delay =
        last.get().status().isSuccess()
            ? instance.schedule().collectIntervalOr(options.interval())
            : retryDelay(runs.recentFailureCount(id));
    return Optional.of(last.get().startedAt().plus(delay));
  }

  /** min(retryMax, retryBase·2^(max(0, failures−1))). */
  Duration retryDelay(int recentFailures) {
    return new ExponentialBackoff(options.retryBase(), options.retryMax(), 30)
        .afterFailures(recentFailures);
  }

  CollectionMode modeAt(SourceInstance instance, Instant now) {
    Optional<Duration> interval =
        instance.schedule().snapshotIntervalOr(options.snapshotInterval());
    if (interval.isEmpty()) return CollectionMode.INCREMENTAL;
    Optional<Instant> last = lastSnapshotAt(instance.id());
    if (last.isEmpty()) return CollectionMode.SNAPSHOT;
    return last.get().plus(interval.get()).isAfter(now)
        ? CollectionMode.INCREMENTAL
        : CollectionMode.SNAPSHOT;
  }

  /** 스냅샷 모드로 집었다 — 완료하지 못해도 간격 동안 다시 스냅샷으로 집지 않는다. */
  void snapshotAttempted(SourceInstanceId id, Instant at) {
    lastSnapshotAttemptAt.put(id, at);
  }

  /** 마지막 스냅샷 완료와 마지막 스냅샷 시도 중 늦은 시각(같으면 완료). */
  private Optional<Instant> lastSnapshotAt(SourceInstanceId id) {
    Optional<Instant> completed =
        runs.lastSnapshotCompletion(id).map(RunStore.SnapshotCompletion::completedAt);
    Optional<Instant> attempted = Optional.ofNullable(lastSnapshotAttemptAt.get(id));
    if (completed.isEmpty()) return attempted;
    return Optional.of(attempted.filter(a -> a.isAfter(completed.get())).orElse(completed.get()));
  }
}
