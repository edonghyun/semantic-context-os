package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 유지보수 유예(F9·F10): 0일 이상, cutoff = now − 일수, 명령 인자(--grace-days)는 음이 아닌 정수만. */
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
  @DisplayName("데몬 설정의 기본 유예 일수(DEFAULT_DAYS)는 \"30\"이다")
  void defaultDaysIsThirty() {
    // then
    assertThat(GracePeriod.DEFAULT_DAYS).isEqualTo("30");
  }

  @ParameterizedTest(name = "\"{0}\" → {1}일")
  @CsvSource({"0, 0", "7, 7"})
  @DisplayName("parseDays는 음이 아닌 정수를 그 일수로 읽는다(0 포함)")
  void parsesDays(String raw, int days) {
    // when
    GracePeriod grace = GracePeriod.parseDays(raw);

    // then
    assertThat(grace).isEqualTo(GracePeriod.ofDays(days));
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"abc", "-1", "", "1.5"})
  @DisplayName("parseDays는 음이 아닌 정수가 아닌 입력을 밝혀 거부한다")
  void parseRejectsNonNegativeIntegerViolations(String raw) {
    // when·then
    assertThatThrownBy(() -> GracePeriod.parseDays(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("--grace-days must be a non-negative integer: " + raw);
  }
}
