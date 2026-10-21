package io.scos.support;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 지표 단언 어휘 자체의 계약(TESTING §8, F15 T15.2 — tr-vocab-harness High 1·2): 메서드마다 맞는 입력에서 통과하고 틀린 입력에서
 * 실패하며, 조건에 맞는 계열이 둘 이상이면 어느 쪽을 단언하는지 모호하므로 실패한다.
 */
@DisplayName("지표 단언은 맞으면 통과하고 틀리거나 모호하면 실패한다(TESTING §8)")
class MeterRegistryAssertTest {
  static final String GAUGE = "scos.test.gauge";
  static final String COUNTER = "scos.test.calls";
  static final String TIMER = "scos.test.duration";
  static final Tags PENDING = Tags.of("state", "pending");
  static final Tags OK_FETCH = Tags.of("outcome", "ok", "operation", "fetch");
  static final Tags OK_LIST = Tags.of("outcome", "ok", "operation", "list");

  final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  void givenGauge(Tags tags, double value) {
    Gauge.builder(GAUGE, () -> value).tags(tags).register(registry);
  }

  void givenCounter(Tags tags, double count) {
    registry.counter(COUNTER, tags).increment(count);
  }

  void givenTimer(Tags tags, int records) {
    Timer timer = registry.timer(TIMER, tags);
    for (int i = 0; i < records; i++) timer.record(Duration.ZERO);
  }

  @Nested
  @DisplayName("hasCounter(이름, 태그 하나)는")
  class HasCounterByOneTag {
    @Test
    @DisplayName("값이 같으면 통과한다")
    void passesOnSameCount() {
      // given
      givenCounter(OK_FETCH, 2);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasCounter(COUNTER, "operation", "fetch", 2))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 다르면 기대값과 실제값을 밝혀 실패한다")
    void failsOnDifferentCount() {
      // given
      givenCounter(OK_FETCH, 2);

      // when·then
      assertThatThrownBy(
              () -> assertThatMetrics(registry).hasCounter(COUNTER, "operation", "fetch", 3))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("3.0이어야 하는데 2.0");
    }

    @Test
    @DisplayName("없으면 실패한다")
    void failsWhenMissing() {
      // when·then
      assertThatThrownBy(
              () -> assertThatMetrics(registry).hasCounter(COUNTER, "operation", "fetch", 0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("없다");
    }

    @Test
    @DisplayName("태그 하나에 맞는 계열이 둘이면 모호하다고 실패한다")
    void failsWhenAmbiguous() {
      // given: outcome=ok는 fetch·list 두 계열에 붙어 있다
      givenCounter(OK_FETCH, 1);
      givenCounter(OK_LIST, 1);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasCounter(COUNTER, "outcome", "ok", 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("2개 계열");
    }
  }

  @Nested
  @DisplayName("hasCounter(이름, 태그들)는")
  class HasCounterByTags {
    @Test
    @DisplayName("태그를 모두 지정하면 그 계열의 값을 본다")
    void picksExactSeries() {
      // given
      givenCounter(OK_FETCH, 1);
      givenCounter(OK_LIST, 4);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasCounter(COUNTER, OK_LIST, 4))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 다르면 실패한다")
    void failsOnDifferentCount() {
      // given
      givenCounter(OK_LIST, 4);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasCounter(COUNTER, OK_LIST, 1))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("일부 태그에 맞는 계열이 둘이면 모호하다고 실패한다")
    void failsWhenAmbiguous() {
      // given
      givenCounter(OK_FETCH, 1);
      givenCounter(OK_LIST, 1);

      // when·then
      assertThatThrownBy(
              () -> assertThatMetrics(registry).hasCounter(COUNTER, Tags.of("outcome", "ok"), 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("2개 계열");
    }
  }

  @Nested
  @DisplayName("hasNoCounter는")
  class HasNoCounter {
    @Test
    @DisplayName("등록되지 않았으면 통과한다")
    void passesWhenAbsent() {
      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasNoCounter(COUNTER, "operation", "fetch"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("0으로 등록된 카운터가 있으면 실패한다")
    void failsOnZeroRegistered() {
      // given
      givenCounter(OK_FETCH, 0);

      // when·then
      assertThatThrownBy(
              () -> assertThatMetrics(registry).hasNoCounter(COUNTER, "operation", "fetch"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("0.0로 있다");
    }
  }

  @Nested
  @DisplayName("hasNoMeter는")
  class HasNoMeter {
    @Test
    @DisplayName("그 이름의 지표가 없으면 통과한다")
    void passesWhenAbsent() {
      // given: 다른 이름의 지표만 있다
      givenGauge(PENDING, 1);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasNoMeter(COUNTER))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("태그와 무관하게 하나라도 있으면 실패한다")
    void failsWhenAnyExists() {
      // given
      givenCounter(OK_LIST, 0);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasNoMeter(COUNTER))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("hasTimerCount(이름)는")
  class HasTimerCountByName {
    @Test
    @DisplayName("기록 수가 같으면 통과한다")
    void passesOnSameCount() {
      // given
      givenTimer(OK_FETCH, 2);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasTimerCount(TIMER, 2))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기록 수가 다르면 실패한다")
    void failsOnDifferentCount() {
      // given
      givenTimer(OK_FETCH, 2);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasTimerCount(TIMER, 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("1이어야 하는데 2");
    }

    @Test
    @DisplayName("태그가 다른 계열이 둘이면 모호하다고 실패한다")
    void failsWhenAmbiguous() {
      // given
      givenTimer(OK_FETCH, 1);
      givenTimer(OK_LIST, 1);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasTimerCount(TIMER, 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("2개 계열");
    }
  }

  @Nested
  @DisplayName("hasTimerCount(이름, 태그들)는")
  class HasTimerCountByTags {
    @Test
    @DisplayName("태그로 고른 계열의 기록 수를 본다")
    void picksExactSeries() {
      // given
      givenTimer(OK_FETCH, 1);
      givenTimer(OK_LIST, 3);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasTimerCount(TIMER, OK_LIST, 3))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("없으면 실패한다")
    void failsWhenMissing() {
      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasTimerCount(TIMER, OK_LIST, 0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("없다");
    }
  }

  @Nested
  @DisplayName("hasNoTimer는")
  class HasNoTimer {
    @Test
    @DisplayName("등록되지 않았으면 통과한다")
    void passesWhenAbsent() {
      // given: 다른 계열만 있다
      givenTimer(OK_FETCH, 1);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasNoTimer(TIMER, OK_LIST))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기록 0번으로 등록된 타이머가 있으면 실패한다")
    void failsOnRegistered() {
      // given
      givenTimer(OK_LIST, 0);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasNoTimer(TIMER, OK_LIST))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("hasGauge는")
  class HasGauge {
    @Test
    @DisplayName("태그로 고른 게이지 값이 같으면 통과한다")
    void passesOnSameValue() {
      // given
      givenGauge(PENDING, 2);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasGauge(GAUGE, PENDING, 2))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 다르면 기대값과 실제값을 밝혀 실패한다")
    void failsOnDifferentValue() {
      // given
      givenGauge(PENDING, 2);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasGauge(GAUGE, PENDING, 3))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("3.0")
          .hasMessageContaining("2.0");
    }

    @Test
    @DisplayName("게이지가 없으면 그 이름과 태그를 밝혀 실패한다")
    void failsWhenMissing() {
      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasGauge(GAUGE, PENDING, 2))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining(GAUGE);
    }

    @Test
    @DisplayName("일부 태그에 맞는 게이지가 둘이면 모호하다고 실패한다")
    void failsWhenAmbiguous() {
      // given
      givenGauge(Tags.of("state", "pending", "source", "a"), 1);
      givenGauge(Tags.of("state", "pending", "source", "b"), 2);

      // when·then
      assertThatThrownBy(() -> assertThatMetrics(registry).hasGauge(GAUGE, PENDING, 1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("2개 계열");
    }

    @Test
    @DisplayName("NaN은 NaN 기대와 같다(모름을 0과 구분한다)")
    void nanEqualsNan() {
      // given
      givenGauge(Tags.empty(), Double.NaN);

      // when·then
      assertThatCode(() -> assertThatMetrics(registry).hasGauge(GAUGE, Tags.empty(), Double.NaN))
          .doesNotThrowAnyException();
    }
  }
}
