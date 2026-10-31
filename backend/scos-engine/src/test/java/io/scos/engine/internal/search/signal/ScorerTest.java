package io.scos.engine.internal.search.signal;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.Effort;
import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.plan.SearchSettings;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** CONTRACTS B8·F-7: 신호 곱 + compacted 동점 가산 + 점수 내림차순. 고정 시각 2026-10-13. */
@DisplayName("Scorer는 기본 점수에 신호 계수를 곱하고 점수 내림차순으로 정렬한다")
class ScorerTest {
  static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  final Scorer scorer = new Scorer(List.of(new RecencySignal()));

  static RetrievalPlan planWithRecency(boolean on) {
    return new RetrievalPlan(Effort.MEDIUM, SearchSettings.DEFAULT, on, true);
  }

  @Test
  @DisplayName("날짜 가산이 꺼져 있으면 점수는 그대로고 순서는 점수 내림차순이다")
  void plainRanksByScore() {
    // given
    List<Candidate> in =
        List.of(
            new Candidate(unit("low").capturedAt("2026-10-12T00:00:00.000Z").build(), 0.1),
            new Candidate(unit("high").capturedAt("2020-01-01T00:00:00.000Z").build(), 0.5));

    // when
    List<Candidate> out = scorer.score(in, planWithRecency(false), NOW);

    // then
    assertThat(out).extracting(c -> c.unit().id()).containsExactly("high", "low");
    assertThat(out).extracting(Candidate::baseScore).containsExactly(0.5, 0.1);
  }

  @Test
  @DisplayName("동점은 입력 순서를 지킨다(안정 정렬)")
  void tiesKeepInputOrder() {
    // given
    List<Candidate> in =
        List.of(
            new Candidate(unit("a").build(), 1.0),
            new Candidate(unit("b").build(), 1.0),
            new Candidate(unit("c").build(), 1.0));

    // when
    List<Candidate> out = scorer.score(in, planWithRecency(false), NOW);

    // then
    assertThat(out).extracting(c -> c.unit().id()).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("날짜 가산이 꺼져 있으면 compacted 동점 가산도 없다")
  void noRecencyMeansNoTieBreak() {
    // given
    List<Candidate> in =
        List.of(
            new Candidate(
                unit("sum").compacted().capturedAt("2026-10-13T00:00:00.000Z").build(), 1.0));

    // when
    List<Candidate> out = scorer.score(in, planWithRecency(false), NOW);

    // then
    assertThat(out.get(0).baseScore()).isEqualTo(1.0);
  }

  @Nested
  @DisplayName("날짜 가산이 켜져 있으면")
  class WithRecency {
    /** 오늘·10일 전·날짜 없는 유닛, 모두 점수 1. */
    static List<Candidate> sameScoreAtThreeAges() {
      return List.of(
          new Candidate(unit("today").capturedAt("2026-10-13T00:00:00.000Z").build(), 1.0),
          new Candidate(unit("old").capturedAt("2026-10-03T00:00:00.000Z").build(), 1.0),
          new Candidate(unit("undated").capturedAt("not a date").build(), 1.0));
    }

    @Test
    @DisplayName("최근 유닛이 앞선다")
    void freshnessReorders() {
      // given
      List<Candidate> in =
          List.of(
              sameScoreAtThreeAges().get(2),
              sameScoreAtThreeAges().get(1),
              sameScoreAtThreeAges().get(0));

      // when
      List<Candidate> out = scorer.score(in, planWithRecency(true), NOW);

      // then
      assertThat(out).extracting(c -> c.unit().id()).containsExactly("today", "old", "undated");
    }

    @Test
    @DisplayName("가중은 1 + 0.1·0.99^days다(날짜 없으면 1)")
    void freshnessWeight() {
      // when
      List<Candidate> out = scorer.score(sameScoreAtThreeAges(), planWithRecency(true), NOW);

      // then
      assertThat(out.get(0).baseScore()).isCloseTo(1.1, within(1e-12));
      assertThat(out.get(1).baseScore())
          .isCloseTo(1.0904382075008805, within(1e-12)); // 1 + 0.1·0.99^10, 10일 전
      assertThat(out.get(2).baseScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("compacted는 같은 점수·같은 날짜의 raw보다 0.1% 앞선다(동점 우선)")
    void compactedTieBreakIsAdditive() {
      // given
      List<Candidate> in =
          List.of(
              new Candidate(unit("raw").capturedAt("2026-10-13T00:00:00.000Z").build(), 1.0),
              new Candidate(
                  unit("sum").capturedAt("2026-10-13T00:00:00.000Z").compacted().build(), 1.0));

      // when
      List<Candidate> out = scorer.score(in, planWithRecency(true), NOW);

      // then
      assertThat(out).extracting(c -> c.unit().id()).containsExactly("sum", "raw");
      assertThat(out.get(0).baseScore()).isCloseTo(1.1 + 0.001, within(1e-12));
    }
  }
}
