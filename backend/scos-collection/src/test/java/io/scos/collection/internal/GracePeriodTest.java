package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 유지보수 유예(F9·F10): 0일 이상, cutoff = now − 일수. 명령 인자(--grace-days) 읽기는 CommandArgumentsTest. */
@DisplayName("유지보수 유예 — cutoff는 now − 일수, 일수는 0 이상(F9·F10)")
class GracePeriodTest {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant THIRTY_DAYS_BEFORE_NOW = Instant.parse("2026-09-14T00:00:00Z");

  @Test
  @DisplayName("cutoff(now)는 now − 일수다")
  void cutoffIsNowMinusDays() {
    // when
    Instant cutoff = GracePeriod.ofDays(30).cutoff(NOW);

    // then
    assertThat(cutoff).isEqualTo(THIRTY_DAYS_BEFORE_NOW);
  }

  @Test
  @DisplayName("0일 유예의 cutoff는 now다")
  void zeroDaysCutsAtNow() {
    // when
    Instant cutoff = GracePeriod.ofDays(0).cutoff(NOW);

    // then
    assertThat(cutoff).isEqualTo(NOW);
  }

  @Test
  @DisplayName("음수 일수는 IllegalArgumentException(값을 밝힘)으로 거부된다")
  void rejectsNegativeDays() {
    // when·then
    assertThatThrownBy(() -> GracePeriod.ofDays(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("grace period must be >= 0 days: -1");
  }

  @Test
  @DisplayName("명령 maintenance의 기본 유예는 30일이다")
  void defaultIsThirtyDays() {
    // then
    assertThat(GracePeriod.DEFAULT).isEqualTo(GracePeriod.ofDays(30));
  }

  @Test
  @DisplayName("데몬 설정의 기본 유예 일수(DEFAULT_DAYS)는 기본 유예와 같다")
  void defaultDaysMatchesDefault() {
    // then
    assertThat(GracePeriod.ofDays(Integer.parseInt(GracePeriod.DEFAULT_DAYS)))
        .isEqualTo(GracePeriod.DEFAULT);
  }
}
