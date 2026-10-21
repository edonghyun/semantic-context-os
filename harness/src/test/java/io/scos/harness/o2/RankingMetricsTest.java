package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 순위 겹침 지표: Jaccard@k(집합)와 RBO(상위 가중, 비결합 목록), 첫 적중 순위. */
@DisplayName("순위 겹침 지표는 손 계산 값과 같다(O2 비교 지표)")
class RankingMetricsTest {
  static final int K = 10;
  static final double P = RankingMetrics.DEFAULT_RBO_P;
  static final double EPSILON = 1e-12;
  static final List<String> ABCD = List.of("a", "b", "c", "d");

  @Nested
  @DisplayName("Jaccard@k는")
  class Jaccard {
    @Test
    @DisplayName("같은 집합이면 1이다(순서와 무관)")
    void sameSetIsOne() {
      // when·then
      assertThat(RankingMetrics.jaccard(ABCD, List.of("d", "c", "b", "a"), K)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("겹침이 없으면 0이다")
    void disjointIsZero() {
      // when·then
      assertThat(RankingMetrics.jaccard(ABCD, List.of("x", "y"), K)).isZero();
    }

    @Test
    @DisplayName("|교집합|/|합집합|이다")
    void partial() {
      // when·then
      assertThat(RankingMetrics.jaccard(List.of("a", "b"), List.of("b", "c"), K))
          .isCloseTo(1.0 / 3, within(EPSILON));
    }

    @Test
    @DisplayName("상위 k개만 본다")
    void onlyTopK() {
      // when·then
      assertThat(RankingMetrics.jaccard(List.of("a", "x"), List.of("a", "y"), 1)).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("RBO는")
  class Rbo {
    @Test
    @DisplayName("같은 순위면 1이다")
    void identicalIsOne() {
      // when·then
      assertThat(RankingMetrics.rbo(ABCD, ABCD, K, P)).isCloseTo(1.0, within(EPSILON));
    }

    @Test
    @DisplayName("겹침이 없으면 0이다")
    void disjointIsZero() {
      // when·then
      assertThat(RankingMetrics.rbo(ABCD, List.of("w", "x", "y", "z"), K, P)).isZero();
    }

    @Test
    @DisplayName("위쪽 순위가 바뀐 쪽이 아래쪽이 바뀐 쪽보다 낮다")
    void topWeighted() {
      // when
      double topSwap = RankingMetrics.rbo(ABCD, List.of("b", "a", "c", "d"), K, P);
      double bottomSwap = RankingMetrics.rbo(ABCD, List.of("a", "b", "d", "c"), K, P);

      // then
      assertThat(topSwap).isLessThan(bottomSwap);
    }

    @Test
    @DisplayName("둘 다 비면 1이다")
    void bothEmptyIsOne() {
      // when·then
      assertThat(RankingMetrics.rbo(List.of(), List.of(), K, P)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한쪽만 비면 0이다")
    void oneEmptyIsZero() {
      // when·then
      assertThat(RankingMetrics.rbo(ABCD, List.of(), K, P)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("1·2위가 바뀌면 손 계산 0.900이다")
    void topSwapValue() {
      // when·then
      assertThat(RankingMetrics.rbo(ABCD, List.of("b", "a", "c", "d"), K, P))
          .isCloseTo(0.9, within(EPSILON));
    }

    @Test
    @DisplayName("3·4위가 바뀌면 손 계산 0.973이다")
    void bottomSwapValue() {
      // when·then
      assertThat(RankingMetrics.rbo(ABCD, List.of("a", "b", "d", "c"), K, P))
          .isCloseTo(0.973, within(EPSILON));
    }

    @Test
    @DisplayName("길이가 다르면 Webber 식 32로 외삽한다(xyz 대 zy = 0.720)")
    void unevenLengths() {
      // when·then
      assertThat(RankingMetrics.rbo(List.of("x", "y", "z"), List.of("z", "y"), K, P))
          .isCloseTo(0.72, within(EPSILON));
    }

    @Test
    @DisplayName("짧은 쪽이 긴 쪽의 앞부분이면 1이다(abcde 대 a)")
    void prefixIsOne() {
      // when·then
      assertThat(RankingMetrics.rbo(List.of("a", "b", "c", "d", "e"), List.of("a"), K, P))
          .isCloseTo(1.0, within(EPSILON));
    }

    @Test
    @DisplayName("깊이 1에서 첫째만 같으면 1이다(외삽)")
    void depthOneMatch() {
      // when·then
      assertThat(RankingMetrics.rbo(List.of("a"), List.of("a"), K, P))
          .isCloseTo(1.0, within(EPSILON));
    }
  }

  @Test
  @DisplayName("첫 적중 순위는 정답 중 처음 나온 것의 1부터 센 순위다")
  void firstHitRank() {
    // when·then
    assertThat(RankingMetrics.firstHitRank(ABCD, List.of("z", "c", "b"))).contains(2);
  }

  @Test
  @DisplayName("정답이 하나도 없으면 첫 적중 순위는 비어 있다")
  void noFirstHit() {
    // when·then
    assertThat(RankingMetrics.firstHitRank(ABCD, List.of("z"))).isEqualTo(Optional.empty());
  }
}
