package io.scos.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.scos.collection.internal.OutboxPort;
import io.scos.engine.Freshness;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.spi.store.SpaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 데몬 게이지(ADR-0009·ADR-0020 §3) — 값은 지표를 읽을 때마다(Actuator 요청) 포트에서 다시 읽는다. 운영 신호: 투영 요청
 * 적체(pending·parked), 결정적으로 실패하는 투영(연속 실패 최댓값, ADR-0018 §11), 데이터 나이(투영 공간들 중 가장 오래된 것, 수집 성공 여부가
 * 아님 — B9와 같은 해석). 나이를 알 수 없으면 NaN(0 = 신선으로 보고하지 않는다).
 */
public final class DaemonGauges implements MeterBinder {
  public static final String PROJECTION_REQUESTS = "scos.projection.requests";
  public static final String CONSECUTIVE_FAILURES = "scos.projection.consecutive_failures";
  public static final String FRESHNESS_HOURS = "scos.engine.freshness_hours";

  /** 한 번 읽은 요청 요약을 게이지 셋이 함께 쓰는 시간 — 지표 요청 한 번에 Outbox 질의 한 번. */
  static final Duration SUMMARY_REUSE = Duration.ofSeconds(1);

  private final OutboxPort outbox;
  private final FreshnessReader freshness;
  private final Set<SpaceId> spaces;
  private final Clock clock;
  private OutboxPort.Summary summary;
  private Instant summaryReadAt;

  public DaemonGauges(
      OutboxPort outbox, FreshnessReader freshness, Set<SpaceId> spaces, Clock clock) {
    this.outbox = outbox;
    this.freshness = freshness;
    this.spaces = Set.copyOf(spaces);
    this.clock = clock;
  }

  /** 최근 SUMMARY_REUSE 안에 읽은 요약이 있으면 그것을, 아니면 새로 읽는다. */
  synchronized OutboxPort.Summary summary() {
    Instant now = clock.instant();
    if (summary == null || !now.isBefore(summaryReadAt.plus(SUMMARY_REUSE))) {
      summary = outbox.summary();
      summaryReadAt = now;
    }
    return summary;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(PROJECTION_REQUESTS, this, g -> g.summary().pending())
        .tag("state", "pending")
        .description("projection requests waiting (not parked, backoff included)")
        .register(registry);
    Gauge.builder(PROJECTION_REQUESTS, this, g -> g.summary().parked())
        .tag("state", "parked")
        .description("projection requests parked after maxAttempts")
        .register(registry);
    Gauge.builder(CONSECUTIVE_FAILURES, this, g -> g.summary().maxConsecutiveFailures())
        .description("largest consecutive projection failure count among requests")
        .register(registry);
    Gauge.builder(FRESHNESS_HOURS, this, DaemonGauges::oldestDataHours)
        .description("age in hours of the stalest projected space (NaN if unknown)")
        .baseUnit("hours")
        .register(registry);
  }

  private double oldestDataHours() {
    return spaces.stream()
        .map(freshness::read)
        .map(Freshness::staleHours)
        .flatMap(Optional::stream)
        .mapToDouble(Double::doubleValue)
        .max()
        .orElse(Double.NaN);
  }
}
