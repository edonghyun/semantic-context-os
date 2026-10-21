package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.scos.support.RankingMetrics.Judged;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 테스트 지원 산술의 자체 검증 — 품질 판정 테스트가 이 값을 믿는다. */
@DisplayName("recall@k·MRR 지원 산술은 손 계산과 같다(TESTING §4.2)")
class RankingMetricsTest {
  static final int K = 3;

  @Nested
  @DisplayName("첫 적중 순위가 2·1·4(k 밖)·없음인 네 질의를 k=3으로 재면")
  class FourJudgedQueries {
    List<Judged> judged;

    @BeforeEach
    void givenJudged() {
      judged =
          List.of(
              new Judged("q1", List.of("x", "a"), List.of("a")), // rank 2
              new Judged("q2", List.of("b"), List.of("b")), // rank 1
              new Judged("q3", List.of("x", "y", "z", "c"), List.of("c", "d")), // rank 4 (> k)
              new Judged("q4", List.of("x"), List.of("e"))); // 없음
    }

    @Test
    @DisplayName("recall@k는 기대 문서가 k 안에 처음 나온 질의의 비율이다")
    void recallAtK() {
      // when
      RankingMetrics m = RankingMetrics.of(judged, K);

      // then
      assertThat(m.recallAtK()).isCloseTo(2 / 4.0, within(1e-12));
    }

    @Test
    @DisplayName("MRR은 첫 순위 역수의 평균이다")
    void mrr() {
      // when
      RankingMetrics m = RankingMetrics.of(judged, K);

      // then
      assertThat(m.mrr()).isCloseTo((1 / 2.0 + 1 + 1 / 4.0 + 0) / 4, within(1e-12));
    }

    @Test
    @DisplayName("k 안에 적중하지 못한 질의를 첫 순위와 함께 남긴다")
    void misses() {
      // when
      RankingMetrics m = RankingMetrics.of(judged, K);

      // then
      assertThat(m.misses()).containsExactly("q3@4", "q4@null");
    }
  }

  @Test
  @DisplayName("판정 질의가 없으면 공허한 1.0 대신 예외다")
  void rejectsEmpty() {
    // when·then
    assertThatThrownBy(() -> RankingMetrics.of(List.of(), 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
