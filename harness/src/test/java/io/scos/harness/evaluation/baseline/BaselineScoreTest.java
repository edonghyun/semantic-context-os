package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("기준선 점수는 기준선에 유리하게 센다")
class BaselineScoreTest {
  @Test
  @DisplayName("역순위는 rg·git 중 나은 쪽, recall은 두 상위 k의 합집합이다")
  void favorsBaseline() {
    // when
    var s = BaselineScore.of(List.of("x", "a"), List.of("b"), Set.of("a", "b"), 5);

    // then
    assertThat(s.reciprocalRank()).isEqualTo(1.0);
    assertThat(s.recall()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("상위 k 밖 정답은 세지 않는다")
  void ignoresBeyondK() {
    // when
    var s = BaselineScore.of(List.of("x", "a"), List.of("y", "b"), Set.of("a", "b"), 1);

    // then
    assertThat(s.recall()).isZero();
    assertThat(s.reciprocalRank()).isZero();
    assertThat(s.ndcg()).isZero();
  }

  @Test
  @DisplayName("합집합은 같은 문서를 한 번만 센다")
  void deduplicatesUnion() {
    // when
    var s = BaselineScore.of(List.of("a", "x"), List.of("a", "b"), Set.of("a", "b"), 2);

    // then — 합집합 a, x, b: 1, 3위 정답
    assertThat(s.recall()).isEqualTo(1.0);
    assertThat(s.ndcg()).isCloseTo((1 + 0.5) / (1 + 1 / (Math.log(3) / Math.log(2))), within(1e-9));
  }

  @Test
  @DisplayName("두 목록이 비어 있으면 모두 0이다")
  void emptyListsScoreZero() {
    // when
    var s = BaselineScore.of(List.of(), List.of(), Set.of("a"), 5);

    // then
    assertThat(s).isEqualTo(new BaselineScore(0, 0, 0));
  }
}
