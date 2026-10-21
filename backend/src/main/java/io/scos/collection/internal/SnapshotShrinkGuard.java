package io.scos.collection.internal;

import io.scos.engine.support.text.EventLog;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 스냅샷 축소 보호(ADR-0028, TS와 다른 의도한 차이). succeeded + complete_snapshot 완료가 정리하기 직전에 정리 대상 수를 소스
 * 기준({@link SnapshotShrinkOverrides} → 데몬 기본값)과 비교해, 너무 많이 지우려 하면 정리를 건너뛰게 한다. 운영자가 허용 표시를 남겼으면 다음
 * 정리 한 번은 기준 없이 진행하고 표시를 소모한다. 판정({@link #judge})은 완료 트랜잭션 안에서, 보고({@link #report})는 커밋 뒤에 부른다.
 */
public final class SnapshotShrinkGuard {
  /** 보호된 run의 errorKind(run은 succeeded, errorCount 0 — CONTRACTS "run errorKind 매핑"). */
  public static final String ERROR_KIND = "snapshot_shrink_guarded";

  /** 허용 표시의 기본 유효 기간(ADR-0028 §4). 이보다 오래 묵은 허용은 쓰지 않는다. */
  public static final Duration DEFAULT_ACCEPT_TTL = Duration.ofDays(7);

  /** 정리 없이 이어진 보호가 이 횟수에 이르면 error로 올린다(ADR-0028 §3). */
  public static final int REPEATED_AFTER = 3;

  private static final Logger log = LoggerFactory.getLogger(SnapshotShrinkGuard.class);

  /** 완료가 정리를 어떻게 할지. */
  public sealed interface Verdict {
    /** 이 완료가 만료로 무시한 허용 표시의 허용 시각. */
    Optional<Instant> expiredAcceptance();

    /** 정리한다. accepted면 운영자 허용 표시를 이 run이 소모했다. */
    record Reconcile(
        boolean accepted, int activeCount, int unobservedCount, Optional<Instant> expiredAcceptance)
        implements Verdict {}

    /** 정리하지 않는다. consecutiveCount는 정리 없이 이어진 보호 횟수(이번 포함). */
    record Guarded(
        SnapshotShrinkLimits limits,
        int activeCount,
        int unobservedCount,
        int consecutiveCount,
        Optional<Instant> expiredAcceptance)
        implements Verdict {
      /** 연속 보호가 {@link #REPEATED_AFTER}회에 이르렀다. */
      public boolean repeated() {
        return consecutiveCount >= REPEATED_AFTER;
      }

      /** run errorSummary(비밀·원문 없음, 수만). */
      public String summary() {
        return "complete snapshot reconcile skipped: "
            + unobservedCount
            + " of "
            + activeCount
            + " active items unobserved (limit "
            + limits.maxDeletePercent()
            + "% at "
            + limits.minActive()
            + "+ items)";
      }
    }
  }

  private final SourceInstanceStore sources;
  private final SnapshotGuardStore store;
  private final SnapshotShrinkLimits defaults;
  private final CollectionMetrics metrics;
  private final Duration acceptTtl;

  /** 허용 유효 기간은 기본값({@link #DEFAULT_ACCEPT_TTL}). */
  public SnapshotShrinkGuard(
      SourceInstanceStore sources,
      SnapshotGuardStore store,
      SnapshotShrinkLimits defaults,
      CollectionMetrics metrics) {
    this(sources, store, defaults, metrics, DEFAULT_ACCEPT_TTL);
  }

  public SnapshotShrinkGuard(
      SourceInstanceStore sources,
      SnapshotGuardStore store,
      SnapshotShrinkLimits defaults,
      CollectionMetrics metrics,
      Duration acceptTtl) {
    if (acceptTtl.isNegative() || acceptTtl.isZero())
      throw new IllegalArgumentException("acceptTtl must be positive");
    this.sources = sources;
    this.store = store;
    this.defaults = defaults;
    this.metrics = metrics;
    this.acceptTtl = acceptTtl;
  }

  /**
   * 완료 트랜잭션 안에서: 정리할지 정하고 보호 기록·허용 소모·허용 만료를 같은 트랜잭션에 남긴다. 허용 표시는 정리 여부와 상관없이 이 완료가 소모한다(다음 정리 한 번만
   * 기준을 건너뛴다). 허용한 지 acceptTtl이 지났으면 쓰지 않고 만료로 표시한다.
   */
  Verdict judge(
      SourceInstanceId source, RunId run, int activeNow, int unobservedCount, Instant at) {
    // 분모는 이 run이 시작할 때의 active 수 — 이번 run의 신규 upsert가 기존 항목의 대량 부재를 희석하지 않게(ADR-0028 §1). 기록이
    // 없으면(시작 기록 이전의 run) 정리 직전 active 수
    int activeCount = store.baseline(source, run).orElse(activeNow);
    Optional<Instant> pendingSince =
        store
            .lastAcceptance(source)
            .filter(SnapshotGuardStore.Acceptance::pending)
            .map(SnapshotGuardStore.Acceptance::acceptedAt);
    Optional<Instant> expired = Optional.empty();
    if (pendingSince.isPresent() && !at.isBefore(pendingSince.get().plus(acceptTtl))) {
      store.expireAcceptance(source, pendingSince.get(), at);
      expired = pendingSince;
    } else if (store.consumeAcceptance(source, run, at)) {
      store.clearGuarded(source);
      return new Verdict.Reconcile(true, activeCount, unobservedCount, Optional.empty());
    }
    SnapshotShrinkLimits limits =
        sources.get(source).map(instance -> instance.shrinkGuard().over(defaults)).orElse(defaults);
    if (limits.protects(activeCount, unobservedCount)) {
      int consecutive =
          store.lastGuarded(source).map(SnapshotGuardStore.Guarded::consecutiveCount).orElse(0) + 1;
      store.recordGuarded(
          source,
          new SnapshotGuardStore.Guarded(run, at, unobservedCount, activeCount, consecutive));
      return new Verdict.Guarded(limits, activeCount, unobservedCount, consecutive, expired);
    }
    store.clearGuarded(source);
    return new Verdict.Reconcile(false, activeCount, unobservedCount, expired);
  }

  /** complete_snapshot run이 시작할 때: 판정 분모를 남긴다. */
  void recordBaseline(SourceInstanceId source, RunId run, int activeCount) {
    store.recordBaseline(source, run, activeCount);
  }

  /** 소스별 값이 없을 때의 기준(설정 `scos.collection.snapshot-guard`). */
  public SnapshotShrinkLimits defaults() {
    return defaults;
  }

  /** 허용 표시의 유효 기간(설정 `scos.collection.snapshot-guard.accept-ttl`). */
  public Duration acceptTtl() {
    return acceptTtl;
  }

  /** 커밋 뒤: 보호·연속 보호·허용 사용·허용 만료를 운영 이벤트와 지표로 알린다. 그냥 정리한 run은 알리지 않는다. */
  void report(SourceInstanceId source, RunId run, Verdict verdict) {
    verdict
        .expiredAcceptance()
        .ifPresent(
            acceptedAt ->
                EventLog.warn(log, "collection.snapshot.shrink_accept_expired")
                    .source(source)
                    .run(run.value())
                    .detail(EventLog.detail("acceptedAt", acceptedAt.toString()))
                    .log());
    if (verdict instanceof Verdict.Guarded g) {
      metrics.reconcileGuarded();
      EventLog.warn(log, "collection.snapshot.reconcile_guarded")
          .source(source)
          .run(run.value())
          .detail(
              EventLog.detail(
                  "unobservedCount",
                  g.unobservedCount(),
                  "activeCount",
                  g.activeCount(),
                  "minActive",
                  g.limits().minActive(),
                  "maxDeletePercent",
                  g.limits().maxDeletePercent()))
          .log();
      if (g.repeated())
        EventLog.error(log, "collection.snapshot.reconcile_guarded_repeatedly")
            .source(source)
            .run(run.value())
            .detail(
                EventLog.detail(
                    "consecutiveCount",
                    g.consecutiveCount(),
                    "unobservedCount",
                    g.unobservedCount(),
                    "activeCount",
                    g.activeCount()))
            .log();
    } else if (verdict instanceof Verdict.Reconcile r && r.accepted())
      EventLog.warn(log, "collection.snapshot.shrink_accept_used")
          .source(source)
          .run(run.value())
          .detail(
              EventLog.detail("deletedCount", r.unobservedCount(), "activeCount", r.activeCount()))
          .log();
  }

  /**
   * 운영자 명령(`source accept-shrink <id>`): 다음 succeeded + complete_snapshot 완료 한 번이 기준 없이 정리하게 한다. 감사
   * 이벤트를 남긴다. 없는 소스는 거부한다.
   */
  public void accept(SourceInstanceId source, Instant at) {
    if (sources.get(source).isEmpty())
      throw new IllegalArgumentException("Source instance not found: " + source.value());
    store.acceptShrink(source, at);
    EventLog.warn(log, "collection.snapshot.shrink_accepted").source(source).log();
  }
}
