package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("접근 관측 상한은 양수인 기간만 받는다")
class AccessAgeOptionsTest {
  static Stream<Duration> invalidAges() {
    return Stream.of(Duration.ZERO, Duration.ofSeconds(-1));
  }

  @ParameterizedTest
  @MethodSource("invalidAges")
  @DisplayName("0 또는 음수인 상한은 의미를 밝혀 거부한다")
  void rejectsNonpositiveAge(Duration maxAge) {
    // when·then
    assertThatThrownBy(() -> new AccessCollection.Options(maxAge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxAge must be positive");
  }
}
