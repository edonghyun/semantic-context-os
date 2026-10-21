package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 불변식 7·ADR-0018 §6: backoff = min(max, base·2^(attempts−1)), parked는 attempts ≥ maxAttempts. */
@DisplayName("투영 재시도 정책 — 지수 backoff와 parked 기준(불변식 7·ADR-0018 §6)")
class ProjectionRetryPolicyTest {
  static final Duration BASE = Duration.ofSeconds(10);
  static final Duration MAX = Duration.ofMinutes(5);
  ProjectionRetryPolicy policy = new ProjectionRetryPolicy(BASE, MAX, 3);

  @Test
  @DisplayName("기본값은 base 10s·상한 5분·10회다")
  void defaults() {
    // when
    ProjectionRetryPolicy defaults = ProjectionRetryPolicy.defaults();

    // then
    assertThat(defaults).isEqualTo(new ProjectionRetryPolicy(BASE, MAX, 10));
  }

  @ParameterizedTest(name = "attempts {0} → {1}")
  @CsvSource({"1, PT10S", "2, PT20S", "3, PT40S", "5, PT160S", "6, PT5M", "40, PT5M"})
  @DisplayName("backoff는 상한(5분)까지 base·2^(attempts−1)이다")
  void backoffDoublesUpToMax(int attempts, Duration expected) {
    // when
    Duration backoff = policy.backoff(attempts);

    // then
    assertThat(backoff).isEqualTo(expected);
  }

  @ParameterizedTest(name = "attempts {0} → parked {1}")
  @CsvSource({"2, false", "3, true", "4, true"})
  @DisplayName("parked는 attempts가 maxAttempts(3)에 이르렀을 때부터다")
  void parksAtMaxAttempts(int attempts, boolean parked) {
    // when
    boolean parks = policy.parks(attempts);

    // then
    assertThat(parks).isEqualTo(parked);
  }

  @Test
  @DisplayName("maxAttempts는 1 이상이어야 한다")
  void rejectsZeroAttempts() {
    // when·then
    assertThatThrownBy(() -> new ProjectionRetryPolicy(BASE, MAX, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
