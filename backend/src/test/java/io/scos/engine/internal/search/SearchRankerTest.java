package io.scos.engine.internal.search;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import io.scos.engine.support.text.Text;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CONTRACTS B8·F-7: 최신성 표지 판정 표(TS SearchRanker 정규식)와 날짜 가중 랭킹. 고정 시각 2026-10-13(10월). */
@DisplayName("최신성 표지가 있는 질의만 날짜 가중으로 다시 정렬한다(CONTRACTS B8·F-7)")
class SearchRankerTest {
  static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  SearchRanker ranker = new SearchRanker();

  static boolean prefersRecent(String query) {
    return SearchRanker.prefersRecent(Text.nfkcLower(query), NOW);
  }

  @ParameterizedTest(name = "\"{0}\" → {1}")
  @CsvSource({
    "최근 정산 현황, true",
    "요즘 결제 장애, true",
    "이번 주 배포, true",
    "이번주 배포, true",
    "이번 달의 지표, true",
    "latest incident, true",
    "this month report, true",
    "주간 보고, true",
    "weekly report, true",
    "일간 지표, true",
    "정산 현황, false",
    "배포 방법, false",
    "온보딩 가이드, false",
    "deployment guide, false",
    "2025년 결제 장애, false",
    "작년 정산, false",
    "지난 분기 매출, false",
    "last incident, false",
    "10월 15일 회의, false",
    "10/15 회의, false",
    "최근 10월 배포, true",
    "최근 9월 배포, false",
    "최근 배포 방법, true",
    "이번 주 배포 방법, true",
    "주간 보고 작성법, false"
  })
  @DisplayName("질의의 최신성 표지를 판정한다")
  void prefersRecentTable(String query, boolean expected) {
    // when: 과거·다른 달·특정일·방법류는 false, 최근 표지·정기 보고는 true
    boolean recent = prefersRecent(query);

    // then
    assertThat(recent).isEqualTo(expected);
  }

  @Test
  @DisplayName("최신성 표지가 없으면 순서는 관련도(점수) 내림차순 그대로다")
  void plainQueryRanksByScore() {
    // given
    List<Candidate> in =
        List.of(
            new Candidate(unit("low").capturedAt("2026-10-12T00:00:00.000Z").build(), 0.1),
            new Candidate(unit("high").capturedAt("2020-01-01T00:00:00.000Z").build(), 0.5));

    // when
    List<Candidate> out = ranker.rank("정산 현황", in, NOW);

    // then
    assertThat(out).extracting(c -> c.unit().id()).containsExactly("high", "low");
    assertThat(out).extracting(Candidate::baseScore).containsExactly(0.5, 0.1);
  }

  @Nested
  @DisplayName("질의에 최신성 표지(최근)가 있으면")
  class RecentQuery {
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
      // when
      List<Candidate> out = ranker.rank("최근 정산", sameScoreAtThreeAges(), NOW);

      // then
      assertThat(out).extracting(c -> c.unit().id()).containsExactly("today", "old", "undated");
    }

    @Test
    @DisplayName("가중은 1 + 0.1·0.99^days다(날짜 없으면 1)")
    void freshnessWeight() {
      // when
      List<Candidate> out = ranker.rank("최근 정산", sameScoreAtThreeAges(), NOW);

      // then
      assertThat(out.get(0).baseScore()).isCloseTo(1.1, within(1e-12));
      assertThat(out.get(1).baseScore())
          .isCloseTo(1.0904382075008805, within(1e-12)); // 1 + 0.1·0.99^10, 10일 전
      assertThat(out.get(2).baseScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("미래 시각(now보다 뒤)은 0일로 본다 — 가중이 1.1배를 넘지 않는다")
    void futureCapturedAtIsClampedToZeroDays() {
      // given
      List<Candidate> in =
          List.of(
              new Candidate(unit("future").capturedAt("2027-01-01T00:00:00.000Z").build(), 1.0));

      // when
      List<Candidate> out = ranker.rank("최근 정산", in, NOW);

      // then
      assertThat(out.get(0).baseScore()).isCloseTo(1.1, within(1e-12));
    }

    @Test
    @DisplayName("compacted는 같은 점수·같은 날짜의 raw보다 0.1% 앞선다(동점 우선)")
    void compactedTieBreak() {
      // given
      List<Candidate> in =
          List.of(
              new Candidate(unit("raw").capturedAt("2026-10-13T00:00:00.000Z").build(), 1.0),
              new Candidate(
                  unit("sum").capturedAt("2026-10-13T00:00:00.000Z").compacted().build(), 1.0));

      // when
      List<Candidate> out = ranker.rank("최근 정산", in, NOW);

      // then
      assertThat(out).extracting(c -> c.unit().id()).containsExactly("sum", "raw");
      assertThat(out.get(0).baseScore()).isCloseTo(1.1 + 0.001, within(1e-12));
    }
  }
}
