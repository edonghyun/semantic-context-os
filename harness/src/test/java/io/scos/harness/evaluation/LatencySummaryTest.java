package io.scos.harness.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("호출 지연 요약은 중앙값과 p95를 nearest-rank로 낸다")
class LatencySummaryTest {
  @Test
  @DisplayName("측정이 없으면 요약도 없다")
  void emptyHasNoSummary() {
    // when
    var summary = LatencySummary.of(List.of());

    // then
    assertThat(summary).isEmpty();
  }

  @Test
  @DisplayName("1~20ms 스무 개의 중앙값은 10, p95는 19다")
  void medianAndP95() {
    // given
    List<Double> ms =
        java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> (double) i).toList();

    // when
    var summary = LatencySummary.of(ms).orElseThrow();

    // then
    assertThat(summary.median()).isEqualTo(10.0);
    assertThat(summary.p95()).isEqualTo(19.0);
  }
}
