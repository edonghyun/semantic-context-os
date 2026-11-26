package io.scos.engine.internal.search;

import io.scos.engine.Freshness;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 엔진 데이터의 나이(CONTRACTS B9). 무제한 범위는 공간의 capturedAt 최댓값, 제한 범위는 요청자 키가 가리키는 원천들의 마지막 성공 수집 완료 시각 중
 * 최솟값을 쓴다. {@link #byCollection}으로 만들면 무제한 범위도 나열한 원천들의 성공 수집 시각 중 최솟값을 쓴다. stale는 12시간 이상이며, 필요한
 * 포트나 시각이 없으면 {@link Freshness#UNAVAILABLE}이다.
 */
public final class FreshnessReader {
  static final double STALE_AFTER_HOURS = 12;

  private final Optional<UnitMaintenance> units;
  private final Optional<SourceFreshness> sources;
  private final Optional<Set<SourceInstanceId>> unrestrictedSources;
  private final Clock clock;

  public FreshnessReader(UnitMaintenance units, Clock clock) {
    this(Optional.of(units), Optional.empty(), clock);
  }

  /** 모드에 필요한 포트만 조립한다. 없는 포트가 필요한 범위는 확인 불가로 처리한다. */
  public FreshnessReader(
      Optional<UnitMaintenance> units, Optional<SourceFreshness> sources, Clock clock) {
    this(units, sources, Optional.empty(), clock);
  }

  private FreshnessReader(
      Optional<UnitMaintenance> units,
      Optional<SourceFreshness> sources,
      Optional<Set<SourceInstanceId>> unrestrictedSources,
      Clock clock) {
    this.units = units;
    this.sources = sources;
    this.unrestrictedSources = unrestrictedSources;
    this.clock = clock;
  }

  /** 무제한 범위도 유닛 시각 대신 이 원천들의 성공 수집 기록으로 판정한다. 제한 범위는 그대로 요청자 키의 원천을 쓴다. */
  public static FreshnessReader byCollection(
      SourceFreshness sources, Set<SourceInstanceId> instances, Clock clock) {
    return new FreshnessReader(
        Optional.empty(), Optional.of(sources), Optional.of(Set.copyOf(instances)), clock);
  }

  public Freshness read(AccessScope access, SpaceId space) {
    Optional<String> latest =
        switch (access) {
          case AccessScope.Unrestricted ignored ->
              unrestrictedSources.isPresent()
                  ? lastSourceSuccess(unrestrictedSources.get())
                  : units.flatMap(maintenance -> maintenance.latestCapturedAt(space));
          case AccessScope.Restricted restricted ->
              lastSourceSuccess(
                  restricted.keys().stream().map(AccessKey::instance).collect(Collectors.toSet()));
        };
    if (latest.isEmpty()) return Freshness.UNAVAILABLE;
    Optional<Instant> captured = new CapturedAt(latest.get()).instant();
    if (captured.isEmpty()) return Freshness.UNAVAILABLE;
    double hours = Math.max(0, (clock.millis() - captured.get().toEpochMilli()) / 3_600_000.0);
    return new Freshness(latest, Optional.of(hours), hours >= STALE_AFTER_HOURS);
  }

  private Optional<String> lastSourceSuccess(Set<SourceInstanceId> instances) {
    if (sources.isEmpty()) return Optional.empty();
    var succeeded = sources.get().lastSucceeded(instances);
    if (!succeeded.keySet().containsAll(instances)) return Optional.empty();
    return succeeded.values().stream().min(Instant::compareTo).map(Instant::toString);
  }
}
