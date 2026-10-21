package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.spi.store.ScoredRef;
import io.scos.store.postgres.TsVectorReference.Comparison;
import io.scos.store.postgres.TsVectorReference.QueryComparison;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * P-3 판정(PostgresVectorIndexTest.TsReference)이 믿는 비교기 자체의 계약(F15 T15.2, tr-projection-engine High
 * 2): 손으로 만든 비교에서 동점군 밖 자리 바뀜·점수 오차·hit 수 차이를 센다.
 */
@DisplayName("TS 벡터 기준 비교기는 동점군 밖 차이만 센다(ADR-0014 P-3)")
class TsVectorReferenceTest {
  /** 상대 오차 비교 허용치(부동소수 반올림만 흡수). */
  static final double RELATIVE_TOLERANCE = 1e-9;

  static ScoredRef hit(String unitId, double score) {
    return new ScoredRef(unitId, "doc", score);
  }

  @Nested
  @DisplayName("자리가 바뀌면")
  class Swapped {
    @Test
    @DisplayName("TS 점수가 다른 두 hit이 바뀌면 둘 다 센다")
    void swapOutsideTiesCounts() {
      // given: TS는 a(0.9) > b(0.8)
      var q =
          new QueryComparison(
              "q", List.of(hit("b", 0.8), hit("a", 0.9)), List.of("a", "b"), List.of(0.9, 0.8));

      // when
      int mismatches = q.mismatchesOutsideTies();

      // then
      assertThat(mismatches).isEqualTo(2);
    }

    @Test
    @DisplayName("TS 동점군(차이 1e-12 이하) 안에서 바뀌면 세지 않는다")
    void swapInsideTiesIsFree() {
      // given: TS는 a·b가 1e-13 차이(동점군)
      var q =
          new QueryComparison(
              "q",
              List.of(hit("b", 0.5), hit("a", 0.5)),
              List.of("a", "b"),
              List.of(0.5 + 1e-13, 0.5));

      // when
      int mismatches = q.mismatchesOutsideTies();

      // then
      assertThat(mismatches).isZero();
    }

    @Test
    @DisplayName("TS에 없는 hit이 끼면 센다")
    void unknownHitCounts() {
      // given
      var q =
          new QueryComparison(
              "q", List.of(hit("a", 0.9), hit("x", 0.8)), List.of("a", "b"), List.of(0.9, 0.8));

      // when
      int mismatches = q.mismatchesOutsideTies();

      // then
      assertThat(mismatches).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("점수 0.5가 1e-5 어긋나면 상대 오차는 2e-5다")
  void relativeErrorSeesScoreDrift() {
    // given
    var q = new QueryComparison("q", List.of(hit("a", 0.5 + 1e-5)), List.of("a"), List.of(0.5));

    // when
    double error = q.maxRelativeError();

    // then
    assertThat(error).isCloseTo(2e-5, within(RELATIVE_TOLERANCE));
  }

  @Test
  @DisplayName("질의마다 오차가 다르면 가장 큰 상대 오차를 낸다")
  void aggregateIsMaximum() {
    // given: 상대 오차 2e-3인 질의와 2e-6인 질의
    var large = new QueryComparison("large", List.of(hit("a", 0.501)), List.of("a"), List.of(0.5));
    var small =
        new QueryComparison("small", List.of(hit("b", 0.5 + 1e-6)), List.of("b"), List.of(0.5));

    // when
    double error = new Comparison(List.of(small, large)).maxRelativeError();

    // then
    assertThat(error).isCloseTo(2e-3, within(RELATIVE_TOLERANCE));
  }

  @Test
  @DisplayName("비교한 hit 수는 질의마다 받은 hit의 합이다")
  void comparedHitsSumsGot() {
    // given: 받은 hit 2건·1건
    var two =
        new QueryComparison(
            "two", List.of(hit("a", 0.9), hit("b", 0.8)), List.of("a", "b"), List.of(0.9, 0.8));
    var one = new QueryComparison("one", List.of(hit("c", 0.7)), List.of("c"), List.of(0.7));

    // when
    int compared = new Comparison(List.of(two, one)).comparedHits();

    // then
    assertThat(compared).isEqualTo(3);
  }

  @Test
  @DisplayName("hit 수가 다른 질의는 그 이름이 나온다")
  void namesQueriesWithDifferentHitCount() {
    // given
    var same = new QueryComparison("same", List.of(hit("a", 0.9)), List.of("a"), List.of(0.9));
    var shorter = new QueryComparison("shorter", List.of(), List.of("a"), List.of(0.9));

    // when
    List<String> different = new Comparison(List.of(same, shorter)).queriesWithDifferentHitCount();

    // then
    assertThat(different).containsExactly("shorter");
  }

  @Test
  @DisplayName("질의마다 동점군 밖 차이를 더한다")
  void sumsMismatchesAcrossQueries() {
    // given: 각 질의에 동점군 밖 교환이 하나씩(2건씩)
    var q1 =
        new QueryComparison(
            "q1", List.of(hit("b", 0.8), hit("a", 0.9)), List.of("a", "b"), List.of(0.9, 0.8));
    var q2 =
        new QueryComparison(
            "q2", List.of(hit("d", 0.2), hit("c", 0.4)), List.of("c", "d"), List.of(0.4, 0.2));

    // when
    int total = new Comparison(List.of(q1, q2)).mismatchesOutsideTies();

    // then
    assertThat(total).isEqualTo(4);
  }
}
