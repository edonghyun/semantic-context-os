package io.scos.harness.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 이진 관련도 지표: Recall@k, 역순위(MRR의 질의 하나), nDCG@k. 기대값은 손으로 계산했다. */
@DisplayName("관련도 지표는 손 계산 값과 같다(판정셋 평가)")
class RelevanceMetricsTest {
  static final double EPSILON = 1e-12;

  @Nested
  @DisplayName("recall@k는")
  class Recall {
    @Test
    @DisplayName("상위 k 안 정답 수를 정답 수로 나눈다")
    void dividesHitsInTopKByRelevantCount() {
      // when·then
      assertThat(RelevanceMetrics.recallAt(List.of("a", "x", "b", "y"), Set.of("a", "b", "c"), 3))
          .isCloseTo(2.0 / 3, within(EPSILON));
    }

    @Test
    @DisplayName("정답이 k 뒤에만 있으면 0이다")
    void relevantOnlyAfterKIsZero() {
      // when·then
      assertThat(RelevanceMetrics.recallAt(List.of("x", "y", "a"), Set.of("a"), 2)).isZero();
    }
  }

  @Nested
  @DisplayName("역순위는")
  class ReciprocalRank {
    @Test
    @DisplayName("상위 k 안 첫 정답 순위의 역수다")
    void inverseOfFirstRelevantRank() {
      // when·then
      assertThat(RelevanceMetrics.reciprocalRankAt(List.of("x", "a", "b"), Set.of("a", "b"), 3))
          .isEqualTo(0.5);
    }

    @Test
    @DisplayName("상위 k 안에 정답이 없으면 0이다")
    void noRelevantInTopKIsZero() {
      // when·then
      assertThat(RelevanceMetrics.reciprocalRankAt(List.of("x", "y", "a"), Set.of("a"), 2))
          .isZero();
    }
  }

  @Nested
  @DisplayName("nDCG@k는")
  class Ndcg {
    @Test
    @DisplayName("이진 관련도 DCG를 이상적 DCG로 나눈다")
    void dcgOverIdealDcg() {
      // when·then
      assertThat(RelevanceMetrics.ndcgAt(List.of("x", "a"), Set.of("a"), 2))
          .isCloseTo(0.6309297535714575, within(EPSILON));
    }

    @Test
    @DisplayName("정답이 k보다 많으면 이상적 DCG는 k개까지만 센다")
    void idealDcgStopsAtK() {
      // when·then
      assertThat(RelevanceMetrics.ndcgAt(List.of("a", "b"), Set.of("a", "b", "c"), 2))
          .isCloseTo(1.0, within(EPSILON));
    }

    @Test
    @DisplayName("결과가 k보다 짧아도 정의대로 계산한다")
    void shortResultFollowsDefinition() {
      // given: DCG = 1, IDCG = 1 + 1/log2(3)
      double ideal = 1 + 1 / (Math.log(3) / Math.log(2));

      // when·then
      assertThat(RelevanceMetrics.ndcgAt(List.of("a"), Set.of("a", "b"), 5))
          .isCloseTo(1 / ideal, within(EPSILON));
    }
  }

  @Test
  @DisplayName("같은 정답 id가 여러 번 있어도 한 번만 센다")
  void duplicateRelevantIdsCountOnce() {
    // when·then
    assertThat(RelevanceMetrics.recallAt(List.of("a"), List.of("a", "a"), 1)).isEqualTo(1.0);
  }
}
