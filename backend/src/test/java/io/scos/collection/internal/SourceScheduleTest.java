package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 소스별 주기 값의 범위(열이 초 단위 양의 INTEGER). */
@DisplayName("소스별 주기는 1초 이상의 초 단위 INTEGER 범위다")
class SourceScheduleTest {
  static Stream<Arguments> outOfRange() {
    return Stream.of(
        Arguments.of("0초", Duration.ZERO),
        Arguments.of("음수", Duration.ofSeconds(-1)),
        Arguments.of("1초 미만 단위", Duration.ofMillis(1500)),
        Arguments.of("INTEGER 초 상한 초과", Duration.ofSeconds(Integer.MAX_VALUE + 1L)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outOfRange")
  @DisplayName("1초 이상의 초 단위가 아닌 주기는 거부한다")
  void rejectsOutOfRange(String name, Duration interval) {
    // when·then
    assertThatThrownBy(() -> new SourceSchedule(Optional.of(interval), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collectInterval");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outOfRange")
  @DisplayName("스냅샷 주기도 같은 범위를 지킨다")
  void rejectsSnapshotOutOfRange(String name, Duration interval) {
    // when·then
    assertThatThrownBy(() -> new SourceSchedule(Optional.empty(), Optional.of(interval)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("snapshotInterval");
  }

  static Stream<Arguments> bounds() {
    return Stream.of(
        Arguments.of("1초", Duration.ofSeconds(1)),
        Arguments.of("INTEGER 초 상한", Duration.ofSeconds(Integer.MAX_VALUE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bounds")
  @DisplayName("범위의 양 끝은 받는다")
  void acceptsBounds(String name, Duration interval) {
    // when
    SourceSchedule schedule = new SourceSchedule(Optional.of(interval), Optional.of(interval));

    // then
    assertThat(schedule.collectInterval()).contains(interval);
  }
}
