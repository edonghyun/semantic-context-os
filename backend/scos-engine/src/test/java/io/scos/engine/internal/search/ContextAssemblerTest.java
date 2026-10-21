package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.internal.search.ContextAssembler.Bundle;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CONTRACTS C: 예산 탐욕 채우기(초과 유닛 건너뜀 → truncated), 요약 우선·드릴다운(원본을 친화도 순, 요약 바로 뒤), 중복·invalidated 제외,
 * U자 배치. 후보 순서는 고정 순위 키워드 색인 대역이 명시한다 — 메모리 색인의 점수 수식에 기대지 않는다(TESTING §3.1).
 */
@DisplayName("맥락 조립은 예산 안에서 요약 우선·드릴다운·U자 배치로 담는다(CONTRACTS C)")
class ContextAssemblerTest {
  static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

  /** 이 파일의 어떤 후보 묶음도 다 담는 예산. */
  static final int AMPLE_BUDGET = 500;

  /** 이 파일의 어떤 후보 집합보다 큰 예산. */
  static final int GENEROUS_BUDGET = 8000;

  /** 10토큰 유닛 하나만 담기는 예산. */
  static final int ONE_SMALL_UNIT = 10;

  /** 400토큰 요약이 넘치는 예산. */
  static final int BELOW_SUMMARY = 300;

  /** 100토큰 요약 + 100토큰 원본 하나만 담기는 예산. */
  static final int SUMMARY_AND_ONE_ORIGINAL = 200;

  /** RRF(k=60)의 1위·2위 점수. */
  static final double RRF_FIRST = 1.0 / 61;

  static final double RRF_SECOND = 1.0 / 62;

  static final double EXACT = 1e-12;

  final SearchFixture fx = new SearchFixture();

  /** 질의와 무관하게 지정한 순위(앞일수록 높은 점수)로 답하는 키워드 색인 — 조립 규칙만 보기 위한 대역. */
  static final class FixedRankKeywordIndex implements KeywordIndex {
    private final List<String> ranked;
    private final Map<String, IndexedText> indexed = new LinkedHashMap<>();

    FixedRankKeywordIndex(String... rankedUnitIds) {
      this.ranked = List.of(rankedUnitIds);
    }

    @Override
    public IndexCapabilities capabilities() {
      return IndexCapabilities.exactTransactional();
    }

    @Override
    public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
      List<ScoredRef> out = new ArrayList<>();
      for (int i = 0; i < ranked.size() && out.size() < k; i++) {
        IndexedText u = indexed.get(ranked.get(i));
        if (u != null && filter.accepts(u.partition(), u.archived()))
          out.add(new ScoredRef(u.unitId(), u.documentId(), 1.0 - 0.01 * i));
      }
      return out;
    }

    @Override
    public void upsert(SpaceId space, List<IndexedText> units) {
      for (IndexedText u : units) indexed.put(u.unitId(), u);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      unitIds.forEach(indexed::remove);
    }
  }

  /** 유닛을 공간 S에 적재한다. */
  void given(ContextUnit... units) {
    fx.load(S, units);
  }

  /** 키워드 후보를 이 순위로 고정한 조립기로 조립한다. */
  Bundle assembleRanked(String query, int budget, String... ranked) {
    return assemblerRanking(ranked).assemble(AccessScope.singleUser(), S, query, budget);
  }

  /** 고정 순위 색인은 fx.load가 채운 메모리 색인의 항목을 그대로 받는다(유닛 집합은 같고 순서만 명시). */
  ContextAssembler assemblerRanking(String... rankedUnitIds) {
    FixedRankKeywordIndex ranking = new FixedRankKeywordIndex(rankedUnitIds);
    ranking.upsert(
        S,
        fx.units.listAll(S).stream()
            .map(
                u ->
                    new IndexedText(
                        u.id(), u.documentId(), u.title(), u.content(), u.partition(), false))
            .toList());
    return new ContextAssembler(fx.units, search(ranking), NOW);
  }

  QuestionSearch search(KeywordIndex keywords) {
    return new QuestionSearch(
        fx.units,
        keywords,
        fx.vectors,
        FixedEmbedder.zero(2),
        Reranker.DISABLED,
        SearchSettings.DEFAULT,
        NOW,
        TestMetrics.search(),
        TestExecutors.CALLER_THREAD);
  }

  static List<String> ids(Bundle b) {
    return b.items().stream().map(i -> i.unit().id()).toList();
  }

  @Nested
  @DisplayName("300·400·200토큰 후보를 예산 500에 순위 순으로 담으면")
  class OverBudget {
    @BeforeEach
    void givenThreeCandidatesOverBudget() {
      given(
          unit("a-1").inDocument("a").content("정산 배치 1").tokens(300).build(),
          unit("b-1").inDocument("b").content("정산 배치 큰 문서").tokens(400).build(),
          unit("c-1").inDocument("c").content("정산 규정").tokens(200).build());
    }

    @Test
    @DisplayName("예산을 넘치는 유닛만 건너뛴다")
    void skipsUnitsThatDoNotFit() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "a-1", "b-1", "c-1");

      // then
      assertThat(ids(b)).containsExactly("a-1", "c-1");
    }

    @Test
    @DisplayName("건너뛴 유닛이 있으므로 truncated다")
    void truncatedWhenSkipped() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "a-1", "b-1", "c-1");

      // then
      assertThat(b.truncated()).isTrue();
    }

    @Test
    @DisplayName("usedTokens는 담긴 유닛의 tokens 합이다")
    void countsUsedTokens() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "a-1", "b-1", "c-1");

      // then
      assertThat(b.usedTokens()).isEqualTo(500);
    }
  }

  @Nested
  @DisplayName("300·400토큰 후보를 넉넉한 예산에 담으면")
  class WithinBudget {
    @BeforeEach
    void givenTwoCandidatesWithinBudget() {
      given(
          unit("a-1").inDocument("a").content("정산 1").tokens(300).build(),
          unit("b-1").inDocument("b").content("정산 2").tokens(400).build());
    }

    @Test
    @DisplayName("후보를 전부 담는다")
    void withinBudgetTakesAll() {
      // when
      Bundle all = assembleRanked("정산", GENEROUS_BUDGET, "a-1", "b-1");

      // then
      assertThat(all.items()).hasSize(2);
    }

    @Test
    @DisplayName("usedTokens는 후보 tokens 합 전체다")
    void withinBudgetUsesAllTokens() {
      // when
      Bundle all = assembleRanked("정산", GENEROUS_BUDGET, "a-1", "b-1");

      // then
      assertThat(all.usedTokens()).isEqualTo(700);
    }

    @Test
    @DisplayName("truncated가 아니다")
    void withinBudgetIsNotTruncated() {
      // when
      Bundle all = assembleRanked("정산", GENEROUS_BUDGET, "a-1", "b-1");

      // then
      assertThat(all.truncated()).isFalse();
    }
  }

  @Nested
  @DisplayName("요약 하나에 원본이 둘이고 예산이 원본 하나만 허용하면")
  class SummaryWithTwoOriginals {
    /**
     * 원본은 후보 순위에 없다(후보였다면 자기 점수로 먼저 담겨 드릴다운은 중복 거부 — TS와 같다). compactedFrom 순서는 o-2가 앞이지만 친화도("정산"
     * 포함)는 o-1이 높다. 예산은 요약 + 원본 하나만 허용한다.
     */
    @BeforeEach
    void givenSummaryWithTwoOriginals() {
      given(
          unit("o-1").inDocument("orig").titled("원본 1").content("정산 자료 하나").tokens(100).build(),
          unit("o-2").inDocument("orig").titled("원본 2").content("무관한 내용").tokens(100).build(),
          unit("s-1")
              .inDocument("sum")
              .titled("정산 요약")
              .content("정산 배치 요약")
              .tokens(100)
              .compacted("o-2", "o-1")
              .build());
    }

    @Test
    @DisplayName("원본을 질의 친화도가 높은 것부터 남은 예산에 담는다")
    void prefersHigherAffinity() {
      // when
      Bundle b = assembleRanked("정산 배치", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-1");
      assertThat(b.usedTokens()).isEqualTo(SUMMARY_AND_ONE_ORIGINAL);
    }

    @Test
    @DisplayName("원본 점수는 요약 바로 아래다")
    void originalScoresJustBelowSummary() {
      // when
      Bundle b = assembleRanked("정산 배치", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(b.items().get(1).rankScore()).isEqualTo(b.items().get(0).rankScore() - 0.0001);
    }

    @Test
    @DisplayName("예산 밖 원본이 남으므로 truncated다")
    void leftoverOriginalTruncates() {
      // when
      Bundle b = assembleRanked("정산 배치", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(b.truncated()).isTrue();
    }
  }

  @Nested
  @DisplayName("담긴 요약의 원본을 드릴다운하면")
  class DrillDown {
    @Test
    @DisplayName("invalidated 원본은 담지 않는다(요약은 담긴다)")
    void skipsInvalidatedOriginal() {
      // given
      given(
          unit("o-dead")
              .inDocument("orig")
              .content("원본")
              .tokens(10)
              .invalidatedAt("2026-10-02T00:00:00.000Z")
              .build(),
          unit("o-live").inDocument("orig").content("원본 2").tokens(10).build(),
          unit("s-1")
              .inDocument("sum")
              .content("정산 요약")
              .tokens(10)
              .compacted("o-dead", "o-live")
              .build());

      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-live");
    }

    @Test
    @DisplayName("원본은 최종 정렬에서 자기 요약 바로 뒤에 끼어든다")
    void originalsFollowTheirSummary() {
      // given: 요약 2개: [s1, o1, s2, o2] → U자 [s1, s2, o2, o1]
      given(
          unit("s-1").inDocument("sum1").content("정산 요약 1").tokens(10).compacted("o-1").build(),
          unit("s-2").inDocument("sum2").content("정산 요약 2").tokens(10).compacted("o-2").build(),
          unit("o-1").inDocument("orig1").content("원본 1").tokens(10).build(),
          unit("o-2").inDocument("orig2").content("원본 2").tokens(10).build());

      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "s-1", "s-2");

      // then
      assertThat(ids(b)).containsExactly("s-1", "s-2", "o-2", "o-1");
    }

    @Test
    @DisplayName("예산에 안 담긴 요약의 원본은 드릴다운되지 않는다")
    void skippedSummaryIsNotDrilledDown() {
      // given
      given(
          unit("o-1").inDocument("orig").content("원본").tokens(100).build(),
          unit("s-1").inDocument("sum").content("정산 요약").tokens(400).compacted("o-1").build());

      // when
      Bundle b = assembleRanked("정산", BELOW_SUMMARY, "s-1");

      // then
      assertThat(b.items()).isEmpty();
      assertThat(b.truncated()).isTrue();
    }
  }

  @Nested
  @DisplayName("100토큰 요약의 원본 중 예산에 하나만 담을 수 있으면")
  class Affinity {
    /** 100토큰 요약 하나와 그 원본들을 적재한다 — 예산은 원본 하나만 허용한다. */
    void givenSummaryOf(ContextUnit... originals) {
      List<ContextUnit> all = new ArrayList<>(List.of(originals));
      all.add(
          unit("s-1")
              .inDocument("sum")
              .content("요약")
              .tokens(100)
              .compacted(all.stream().map(ContextUnit::id).toArray(String[]::new))
              .build());
      given(all.toArray(ContextUnit[]::new));
    }

    static ContextUnit original(String id, String content) {
      return unit(id).inDocument("orig").content(content).tokens(100).build();
    }

    @Test
    @DisplayName("질의 토큰 포함 수가 우선이다")
    void prefersTokenHits() {
      // given
      givenSummaryOf(
          original("o-none", "무관한 내용"),
          original("o-token", "배포 절차"), // 토큰 "배포" 1
          original("o-both", "결제장애일지")); // 토큰 "결제장애" 1 + trigram 2/2

      // when
      Bundle b = assembleRanked("배포 결제장애", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-both");
    }

    @Test
    @DisplayName("토큰 포함이 없으면 공백 무시 trigram 겹침 비율로 가른다")
    void fallsBackToTrigramOverlap() {
      // given
      givenSummaryOf(
          original("o-none", "무관한 내용"), original("o-tri", "제장애 기록")); // "결제장애"의 trigram "제장애" 1/2

      // when
      Bundle b = assembleRanked("결제장애", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-tri");
    }

    @Test
    @DisplayName("trigram 비율(0..1)은 토큰 하나를 넘어서지 못한다")
    void trigramShareNeverOutweighsTokenHit() {
      // given: 질의 trigram 6개: 정산배·산배치 | 온보딩·보딩가·딩가이·가이드
      givenSummaryOf(
          original("o-b", "온보딩가이"), // 토큰 0 + trigram 3/6 = 0.5
          original("o-a", "정산배치")); // 토큰 1 + trigram 2/6 = 1.33

      // when
      Bundle b = assembleRanked("정산배치 온보딩가이드", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-a");
    }

    @Test
    @DisplayName("한 글자 질의 토큰은 세지 않는다(전부 0이면 compactedFrom 순서)")
    void ignoresSingleCharTokens() {
      // given
      givenSummaryOf(original("o-first", "무관한 내용"), original("o-b", "b b b"));

      // when
      Bundle b = assembleRanked("a b", SUMMARY_AND_ONE_ORIGINAL, "s-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-first");
    }
  }

  @Nested
  @DisplayName("30일 묵은 1위와 오늘 날짜의 2위를 기본 랭커로 담으면")
  class FreshnessWeighting {
    @BeforeEach
    void givenMonthOldFirstAndTodaySecond() {
      given(monthOld("old"), today("new"));
    }

    @Test
    @DisplayName("날짜 가중 뒤 다시 정렬해 오늘 날짜가 앞선다")
    void freshnessReorders() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "old", "new");

      // then
      assertThat(ids(b)).containsExactly("new", "old");
    }

    @Test
    @DisplayName("날짜 가중은 1 + 0.1·0.99^days다")
    void freshnessWeight() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "old", "new");

      // then
      assertThat(b.items().get(0).rankScore()).isCloseTo(RRF_SECOND * 1.1, within(EXACT));
      assertThat(b.items().get(1).rankScore())
          .isCloseTo(0.017606066185882428, within(EXACT)); // (1/61)·(1 + 0.1·0.99^30), 30일 전
    }

    @Test
    @DisplayName("예산이 하나만 허용하면 날짜 가중 뒤 순서가 결정한다")
    void freshnessReorderDecidesFill() {
      // when
      Bundle b = assembleRanked("정산", ONE_SMALL_UNIT, "old", "new");

      // then
      assertThat(ids(b)).containsExactly("new");
      assertThat(b.truncated()).isTrue();
    }
  }

  @Nested
  @DisplayName("날짜가 없는 원본 1위와 요약 2위를 기본 랭커로 담으면")
  class UndatedRawAndSummary {
    /** RRF: raw 1/61, sum 1/62 + 티어 밴드 1. */
    @BeforeEach
    void givenUndatedRawAndSummary() {
      given(
          unit("raw").inDocument("r").content("정산").capturedAt("not a date").tokens(10).build(),
          unit("sum")
              .inDocument("s")
              .content("정산 요약")
              .capturedAt("not a date")
              .compacted()
              .tokens(10)
              .build());
    }

    @Test
    @DisplayName("compacted는 점수의 0.1%를 더 받아 동점에서 앞선다")
    void compactedWinsTies() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "raw", "sum");

      // then
      assertThat(ids(b)).containsExactly("sum", "raw");
      assertThat(b.items().get(0).rankScore()).isCloseTo((1 + RRF_SECOND) * 1.001, within(EXACT));
    }

    @Test
    @DisplayName("날짜가 없는 유닛은 가중하지 않는다")
    void undatedIsNotWeighted() {
      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "raw", "sum");

      // then
      assertThat(b.items().get(1).rankScore()).isEqualTo(RRF_FIRST);
    }
  }

  @Nested
  @DisplayName("후보에 담으면 안 되거나 겹치는 유닛이 있으면")
  class Exclusions {
    @Test
    @DisplayName("invalidated 유닛은 후보 순위에 있어도 담지 않는다")
    void excludesInvalidated() {
      // given
      given(
          unit("v-1")
              .inDocument("v")
              .content("정산 폐기")
              .invalidatedAt("2026-10-02T00:00:00.000Z")
              .build(),
          unit("k-1").inDocument("k").content("정산 유효").build());

      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "v-1", "k-1");

      // then
      assertThat(ids(b)).containsExactly("k-1");
    }

    @Test
    @DisplayName("다른 공간의 유닛은 후보가 아니다")
    void excludesOtherSpace() {
      // given
      fx.load(S, unit("k-1").inDocument("k").content("정산 유효").build());
      fx.load(new SpaceId("other"), unit("x-1").inDocument("x").content("정산 다른 공간").build());

      // when
      Bundle b =
          new ContextAssembler(fx.units, search(fx.keywords), NOW)
              .assemble(AccessScope.singleUser(), S, "정산", AMPLE_BUDGET);

      // then
      assertThat(ids(b)).containsExactly("k-1");
    }

    @Test
    @DisplayName("요약의 원본이 후보로도 있으면 자기 점수로 한 번만 담긴다")
    void duplicatesAreAddedOnce() {
      // given
      given(
          unit("o-1").inDocument("orig").content("정산 원본").tokens(100).build(),
          unit("s-1").inDocument("sum").content("정산 요약").tokens(100).compacted("o-1").build());

      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "s-1", "o-1");

      // then
      assertThat(ids(b)).containsExactly("s-1", "o-1");
      assertThat(b.usedTokens()).isEqualTo(SUMMARY_AND_ONE_ORIGINAL);
    }

    @Test
    @DisplayName("요약의 원본이 후보로도 있으면 원본은 자기 순위(2위)의 점수를 받는다")
    void duplicateKeepsOwnRankScore() {
      // given
      given(
          unit("o-1")
              .inDocument("orig")
              .content("정산 원본")
              .capturedAt("2026-10-01T00:00:00.000Z")
              .tokens(100)
              .build(),
          unit("s-1").inDocument("sum").content("정산 요약").tokens(100).compacted("o-1").build());

      // when
      Bundle b = assembleRanked("정산", AMPLE_BUDGET, "s-1", "o-1");

      // then
      assertThat(b.items().get(1).rankScore())
          .isCloseTo(0.017558685276961496, within(EXACT)); // (1/62)·(1 + 0.1·0.99^12), 12일 전
    }
  }

  @Test
  @DisplayName("U자 배치는 순위 1 2 3 4 5를 1 3 5 4 2 순서로 놓는다")
  void frontBackPlacement() {
    // given
    String[] ranked = {"u-1", "u-2", "u-3", "u-4", "u-5"};
    given(smallUnits(List.of(ranked)));

    // when
    Bundle b = assembleRanked("정산", AMPLE_BUDGET, ranked);

    // then
    assertThat(ids(b)).containsExactly("u-1", "u-3", "u-5", "u-4", "u-2");
  }

  /** 문서마다 하나인 10토큰 "정산" 유닛들. */
  static ContextUnit[] smallUnits(List<String> ids) {
    return ids.stream()
        .map(id -> unit(id).inDocument("d-" + id).content("정산 " + id).tokens(10).build())
        .toArray(ContextUnit[]::new);
  }

  static ContextUnit monthOld(String id) {
    return unit(id)
        .inDocument("o")
        .content("정산")
        .capturedAt("2026-09-13T00:00:00.000Z")
        .tokens(10)
        .build();
  }

  static ContextUnit today(String id) {
    return unit(id)
        .inDocument("n")
        .content("정산")
        .capturedAt("2026-10-13T00:00:00.000Z")
        .tokens(10)
        .build();
  }
}
