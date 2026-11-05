package io.scos.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** 등록된 소스의 접근 건강을 조회한다. 한 스크레이프의 게이지들은 1초 동안 같은 상태를 공유한다. */
public final class AccessGauges implements MeterBinder {
  private static final Duration SAMPLE_REUSE = Duration.ofSeconds(1);
  private final SourceQueries queries;
  private final Clock clock;
  private final boolean enabled;
  private final Map<SourceInstanceId, Sample> samples = new HashMap<>();

  private record Sample(Instant readAt, Optional<AccessStatus> status) {}

  public AccessGauges(SourceQueries queries, Clock clock, boolean enabled) {
    this.queries = queries;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    if (!enabled) return;
    for (var source : queries.listSources()) {
      register(registry, source.id(), "scos.access.stale", AccessStatus::rawStale);
      register(
          registry,
          source.id(),
          "scos.access.incomplete_streak",
          AccessStatus::consecutiveIncomplete);
      register(
          registry, source.id(), "scos.access.documents_hidden", AccessStatus::documentsHidden);
      register(registry, source.id(), "scos.access.acl_unreadable", AccessStatus::aclUnreadable);
      register(registry, source.id(), "scos.access.last_complete_age", this::lastCompleteAge);
    }
  }

  private void register(
      MeterRegistry registry,
      SourceInstanceId source,
      String name,
      ToDoubleFunction<AccessStatus> value) {
    Gauge.builder(
            name,
            this,
            gauges ->
                gauges.sample(source).map(status -> value.applyAsDouble(status)).orElse(Double.NaN))
        .tag("source", source.value())
        .strongReference(true)
        .register(registry);
  }

  private synchronized Optional<AccessStatus> sample(SourceInstanceId source) {
    Instant now = clock.instant();
    Sample previous = samples.get(source);
    if (previous == null || !now.isBefore(previous.readAt().plus(SAMPLE_REUSE))) {
      previous = new Sample(now, queries.accessStatus(source));
      samples.put(source, previous);
    }
    return previous.status();
  }

  private double lastCompleteAge(AccessStatus status) {
    return status
        .lastCompleteAt()
        .map(at -> (double) Duration.between(at, clock.instant()).toSeconds())
        .orElse(Double.NaN);
  }
}
