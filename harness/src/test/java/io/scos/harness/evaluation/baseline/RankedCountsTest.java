package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("기준선 순위는 카운트 내림차순과 id 오름차순을 따른다(C10)")
class RankedCountsTest {
  @Test
  @DisplayName("카운트가 다르면 큰 값이 먼저 오고 동점이면 id 순서로 온다")
  void ordersCountsDescendingAndTiesById() {
    // given
    Map<String, Long> counts = Map.of("z", 2L, "b", 5L, "a", 2L, "c", 1L);

    // when
    List<String> ids = RankedCounts.descending(counts);

    // then
    assertThat(ids).containsExactly("b", "a", "z", "c");
  }

  @Test
  @DisplayName("카운트가 없으면 빈 순위를 돌려준다")
  void emptyCountsHaveNoRanks() {
    // given
    Map<String, Long> counts = Map.of();

    // when
    List<String> ids = RankedCounts.descending(counts);

    // then
    assertThat(ids).isEmpty();
  }
}
