package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 스냅샷 축소 보호 기준의 판정(ADR-0028). 기본값은 운영 소스 규모(Notion 154,467·Slack 5,238·Drive 6,825건)에서 공급자 장애로 목록이
 * 절반 넘게 줄어든 경우를 잡고, 10건 미만 소스의 일상적인 삭제는 막지 않는 값이다.
 */
@DisplayName("스냅샷 축소 보호 기준 — 절반 넘게 줄어든 스냅샷과 빈 스냅샷은 정리하지 않는다(ADR-0028)")
class SnapshotShrinkLimitsTest {
  @Nested
  @DisplayName("기본 기준(최소 10건, 상한 50%)에서")
  class DefaultLimits {
    SnapshotShrinkLimits limits;

    @BeforeEach
    void givenDefaults() {
      limits = SnapshotShrinkLimits.DEFAULTS;
    }

    @ParameterizedTest(name = "active {0}건 중 {1}건")
    @CsvSource({"100, 51", "10, 6", "154467, 77234"})
    @DisplayName("지울 비율이 상한을 넘으면 보호한다")
    void protectsOverLimit(int active, int unobserved) {
      // when
      boolean protects = limits.protects(active, unobserved);

      // then
      assertThat(protects).isTrue();
    }

    @ParameterizedTest(name = "active {0}건 중 {1}건")
    @CsvSource({"100, 50", "100, 49", "10, 5", "154467, 77233"})
    @DisplayName("지울 비율이 상한 이하이면 정리한다")
    void reconcilesWithinLimit(int active, int unobserved) {
      // when
      boolean protects = limits.protects(active, unobserved);

      // then
      assertThat(protects).isFalse();
    }

    @ParameterizedTest(name = "active {0}건 중 {1}건")
    @CsvSource({"9, 8", "3, 2"})
    @DisplayName("최소 규모 미만이면 비율과 무관하게 정리한다")
    void reconcilesBelowMinimum(int active, int unobserved) {
      // when
      boolean protects = limits.protects(active, unobserved);

      // then
      assertThat(protects).isFalse();
    }

    @ParameterizedTest(name = "active {0}건 전부")
    @CsvSource({"1", "3", "9", "5238"})
    @DisplayName("active 전부를 지우는 정리는 규모와 무관하게 보호한다")
    void protectsFullWipe(int active) {
      // when
      boolean protects = limits.protects(active, active);

      // then
      assertThat(protects).isTrue();
    }

    @ParameterizedTest(name = "active {0}건")
    @CsvSource({"0", "100"})
    @DisplayName("지울 것이 없으면 보호하지 않는다")
    void nothingToDelete(int active) {
      // when
      boolean protects = limits.protects(active, 0);

      // then
      assertThat(protects).isFalse();
    }
  }

  @Nested
  @DisplayName("기준 값이 범위를 벗어나면")
  class OutOfRange {
    @Test
    @DisplayName("최소 규모가 1보다 작으면 거부한다")
    void rejectsMinimumBelowOne() {
      // when·then
      assertThatThrownBy(() -> new SnapshotShrinkLimits(0, 50))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minActive");
    }

    @ParameterizedTest(name = "{0}%")
    @CsvSource({"0", "101"})
    @DisplayName("상한이 1–100% 밖이면 거부한다")
    void rejectsPercentOutOfRange(int percent) {
      // when·then
      assertThatThrownBy(() -> new SnapshotShrinkLimits(10, percent))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxDeletePercent");
    }
  }

  @Nested
  @DisplayName("소스별 기준이 있으면")
  class Overrides {
    @Test
    @DisplayName("비어 있는 값만 기본값을 따른다")
    void fillsMissingFromDefaults() {
      // given
      SnapshotShrinkOverrides overrides =
          new SnapshotShrinkOverrides(Optional.empty(), Optional.of(80));

      // when
      SnapshotShrinkLimits limits = overrides.over(SnapshotShrinkLimits.DEFAULTS);

      // then
      assertThat(limits).isEqualTo(new SnapshotShrinkLimits(10, 80));
    }

    @Test
    @DisplayName("범위를 벗어난 소스 값은 거부한다")
    void rejectsOutOfRangeOverride() {
      // when·then
      assertThatThrownBy(() -> new SnapshotShrinkOverrides(Optional.of(0), Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
