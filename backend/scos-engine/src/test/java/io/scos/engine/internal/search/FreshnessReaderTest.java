package io.scos.engine.internal.search;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.Freshness;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryUnitStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * CONTRACTS B9: staleHours = (now − 최신 capturedAt)/1h, stale는 12시간 이상(경계 포함), 확인 불가는 UNAVAILABLE.
 */
@DisplayName("신선도는 공간의 최신 capturedAt부터 지금까지로 판정한다(CONTRACTS B9)")
class FreshnessReaderTest {
  static final SpaceId S = new SpaceId("legacy");
  static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
  MemoryUnitStore units = new MemoryUnitStore();
  FreshnessReader reader = new FreshnessReader(units, Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest(name = "latest {0} → staleHours {1}")
  @CsvSource({
    "2026-10-13T11:00:00.000Z, 1.0",
    "2026-10-13T00:00:01.000Z, 11.999722222222223",
    "2026-10-13T00:00:00.000Z, 12.0",
    "2026-10-12T00:00:00.000Z, 36.0",
    "2026-10-13T13:00:00.000Z, 0.0"
  })
  @DisplayName("staleHours는 최신 capturedAt부터 지금까지의 시간이다(미래 시각은 0)")
  void staleHours(String latest, double hours) {
    // given
    units.upsert(S, List.of(unit("u").capturedAt(latest).build()));

    // when
    Freshness f = reader.read(S);

    // then
    assertThat(f.latestCapturedAt()).contains(latest);
    assertThat(f.staleHours()).contains(hours);
  }

  @ParameterizedTest(name = "latest {0} → stale {1}")
  @CsvSource({
    "2026-10-13T11:00:00.000Z, false",
    "2026-10-13T00:00:01.000Z, false",
    "2026-10-13T00:00:00.000Z, true",
    "2026-10-12T00:00:00.000Z, true",
    "2026-10-13T13:00:00.000Z, false"
  })
  @DisplayName("stale는 정확히 12시간부터다")
  void staleBoundary(String latest, boolean stale) {
    // given
    units.upsert(S, List.of(unit("u").capturedAt(latest).build()));

    // when
    Freshness f = reader.read(S);

    // then
    assertThat(f.stale()).isEqualTo(stale);
  }

  @Test
  @DisplayName("공간의 최신 capturedAt(최댓값)을 쓴다")
  void usesLatestAcrossUnits() {
    // given
    units.upsert(
        S,
        List.of(
            unit("a").capturedAt("2026-10-01T00:00:00.000Z").build(),
            unit("b").capturedAt("2026-10-13T11:30:00.000Z").build()));

    // when
    Freshness f = reader.read(S);

    // then
    assertThat(f.latestCapturedAt()).contains("2026-10-13T11:30:00.000Z");
    assertThat(f.staleHours()).contains(0.5);
  }

  @Test
  @DisplayName("유닛이 없으면 UNAVAILABLE이다")
  void emptySpaceIsUnavailable() {
    // when
    Freshness f = reader.read(S);

    // then
    assertThat(f).isEqualTo(Freshness.UNAVAILABLE);
    assertThat(f.staleHours()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("capturedAt을 파싱할 수 없으면(오프셋 없는 문자열 포함, F-4) UNAVAILABLE이다")
  void unparsableIsUnavailable() {
    // given
    units.upsert(S, List.of(unit("u").capturedAt("2026-10-13T11:00:00").build()));

    // when
    Freshness f = reader.read(S);

    // then
    assertThat(f).isEqualTo(Freshness.UNAVAILABLE);
  }
}
