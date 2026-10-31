package io.scos.engine.internal.search.signal;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.internal.search.Candidate;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CONTRACTS F-7: 날짜 가중 계수 1 + 0.1·0.99^days. 고정 시각 2026-10-13. */
@DisplayName("최신성 신호는 수집 뒤 지난 일수로 1 + 0.1·0.99^days를 낸다(CONTRACTS F-7)")
class RecencySignalTest {
  static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  final RecencySignal signal = new RecencySignal();

  static Candidate capturedAt(String capturedAt) {
    return new Candidate(unit("u").capturedAt(capturedAt).build(), 1.0);
  }

  @Test
  @DisplayName("오늘 수집한 유닛의 계수는 1.1이다")
  void todayIsOnePointOne() {
    // given
    Candidate today = capturedAt("2026-10-13T00:00:00.000Z");

    // when
    double factor = signal.factor(today, NOW);

    // then
    assertThat(factor).isCloseTo(1.1, within(1e-12));
  }

  @Test
  @DisplayName("10일 전 수집 유닛의 계수는 기록된 수식과 같다")
  void tenDaysAgoMatchesLegacyFormula() {
    // given
    Candidate tenDaysAgo = capturedAt("2026-10-03T00:00:00.000Z");

    // when
    double factor = signal.factor(tenDaysAgo, NOW);

    // then
    assertThat(factor).isCloseTo(1 + 0.1 * Math.pow(0.99, 10), within(1e-12));
    assertThat(factor).isCloseTo(1.0904382075008805, within(1e-12));
  }

  @Test
  @DisplayName("수집 시각이 잘못되면 계수는 1(영향 없음)이다")
  void badDateIsNeutral() {
    // given
    Candidate undated = capturedAt("not a date");

    // when
    double factor = signal.factor(undated, NOW);

    // then
    assertThat(factor).isEqualTo(1.0);
  }

  @Test
  @DisplayName("미래 시각(now보다 뒤)은 0일로 본다 — 계수가 1.1을 넘지 않는다")
  void futureCapturedAtIsClampedToZeroDays() {
    // given
    Candidate future = capturedAt("2027-01-01T00:00:00.000Z");

    // when
    double factor = signal.factor(future, NOW);

    // then
    assertThat(factor).isCloseTo(1.1, within(1e-12));
  }
}
