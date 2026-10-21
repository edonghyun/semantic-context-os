package io.scos.engine.internal.search;

import io.scos.engine.Freshness;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 엔진 데이터의 나이(CONTRACTS B9, TS FreshnessReader). latestCapturedAt은 공간의 capturedAt 최댓값, staleHours =
 * (now − 그 시각)/1h, stale는 12시간 이상. 확인 불가(유닛 없음·파싱 불가)면 {@link Freshness#UNAVAILABLE}. TS
 * Date.parse는 오프셋 없는 문자열을 로컬로 해석하지만 Java는 거부한다(F-4) — 그 경우도 확인 불가.
 */
public final class FreshnessReader {
  static final double STALE_AFTER_HOURS = 12;

  private final UnitStore units;
  private final Clock clock;

  public FreshnessReader(UnitStore units, Clock clock) {
    this.units = units;
    this.clock = clock;
  }

  public Freshness read(SpaceId space) {
    Optional<String> latest = units.latestCapturedAt(space);
    if (latest.isEmpty()) return Freshness.UNAVAILABLE;
    long parsed;
    try {
      parsed = Instant.parse(latest.get()).toEpochMilli();
    } catch (DateTimeParseException e) {
      return Freshness.UNAVAILABLE;
    }
    double hours = Math.max(0, (clock.millis() - parsed) / 3_600_000.0);
    return new Freshness(latest, Optional.of(hours), hours >= STALE_AFTER_HOURS);
  }
}
