package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.RankingSettings.Recency;
import io.scos.engine.RankingSettings.Recency.When;
import io.scos.engine.RankingSettings.Sources;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 인스턴스가 준 순위 값이 검색 결과를 바꾼다(CONTRACTS B13). 기대 점수는 RRF 1/61·1/62에서 손으로 계산했다. */
@DisplayName("순위 설정은 날짜 계수·문서당 상한·current 창·원천 별명을 바꾼다")
class RankingTest {
  private static RankingSettings settings(Recency recency) {
    return new RankingSettings(recency, 2, 2, Sources.NONE);
  }

  private static RankingSettings settings(int perDocument, int currentWindow) {
    return new RankingSettings(Recency.DEFAULT, perDocument, currentWindow, Sources.NONE);
  }

  private static RankingSettings settings(Sources sources) {
    return new RankingSettings(Recency.DEFAULT, 2, 2, sources);
  }

  @Nested
  @DisplayName("키워드 1위가 10일 전, 2위가 지금 수집된 유닛일 때")
  class Dates {
    @Test
    @DisplayName("세기 0.5면 시간 낱말 질의에 1 + 0.5·0.99^days를 곱한다")
    void weightScalesTheDateFactor() {
      // given
      RankingScenario scenario =
          RankingScenario.with(settings(new Recency(0.5, 0.99, When.MARKED)));
      scenario.givenOlderMatchesMore();
      // when
      var result = scenario.search("최근 정산");
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId, ContextHit::score)
          .containsExactly(
              tuple("newer", (1.0 / 62) * 1.5),
              tuple("older", (1.0 / 61) * (1 + 0.5 * Math.pow(0.99, 10))));
    }

    @Test
    @DisplayName("반감기 10일이면 10일 전 유닛의 가산은 절반이다")
    void halfLifeHalvesTheBoost() {
      // given
      RankingScenario scenario =
          RankingScenario.with(settings(Recency.halfLife(0.1, 10, When.MARKED)));
      scenario.givenOlderMatchesMore();
      // when
      var result = scenario.search("최근 정산");
      // then
      double older =
          result.hits().stream()
              .filter(h -> h.unitId().equals("older"))
              .findFirst()
              .orElseThrow()
              .score();
      assertThat(older).isCloseTo((1.0 / 61) * 1.05, within(1e-15));
    }

    @Test
    @DisplayName("ALWAYS면 시간 낱말이 없는 auto 질의에도 날짜 계수를 곱한다")
    void alwaysAppliesWithoutMarker() {
      // given
      RankingScenario scenario =
          RankingScenario.with(settings(new Recency(0.1, 0.99, When.ALWAYS)));
      scenario.givenOlderMatchesMore();
      // when
      var result = scenario.search("정산");
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId, ContextHit::score)
          .containsExactly(
              tuple("older", (1.0 / 61) * (1 + 0.1 * Math.pow(0.99, 10))),
              tuple("newer", (1.0 / 62) * 1.1));
    }

    @Test
    @DisplayName("current 창이 1이면 limit 1은 관련 1위만 본다")
    void currentWindowOfOneKeepsTheTopHit() {
      // given
      RankingScenario scenario = RankingScenario.with(settings(2, 1));
      scenario.givenOlderMatchesMore();
      // when
      var result = scenario.search("정산", Profile.CURRENT, 1);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("older");
    }
  }

  @Nested
  @DisplayName("한 문서의 조각 셋이 키워드 1~3위일 때")
  class PerDocument {
    @Test
    @DisplayName("상한 1이면 문서마다 첫 조각만 남는다")
    void capOfOne() {
      // given
      RankingScenario scenario = RankingScenario.with(settings(1, 2));
      scenario.givenOneDocumentFillsTheTop();
      // when
      var result = scenario.search("정산");
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("d-1", "x");
    }

    @Test
    @DisplayName("LOW 검색이 MEDIUM으로 올라가도 상한 1이 남는다")
    void capSurvivesEscalation() {
      // given
      RankingScenario scenario = RankingScenario.with(settings(1, 2));
      scenario.givenOneDocumentFillsTheTop();
      // when
      var result = scenario.search("정산", Profile.AUTO, 5, Effort.LOW);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("d-1", "x");
      assertThat(result.applied()).isEqualTo(new AppliedPlan(Effort.MEDIUM, Profile.AUTO, true));
    }

    @Test
    @DisplayName("assemble에도 상한 1이 적용된다")
    void capAppliesToAssemble() {
      // given
      RankingScenario scenario = RankingScenario.with(settings(1, 2));
      scenario.givenOneDocumentFillsTheTop();
      // when
      var bundle = scenario.assemble("정산");
      // then
      assertThat(bundle.items())
          .extracting(BundleItem::unitId)
          .containsExactlyInAnyOrder("d-1", "x");
    }

    @Test
    @DisplayName("상한 0이면 자르지 않는다")
    void zeroMeansNoCap() {
      // given
      RankingScenario scenario = RankingScenario.with(settings(0, 2));
      scenario.givenOneDocumentFillsTheTop();
      // when
      var result = scenario.search("정산");
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .containsExactly("d-1", "d-2", "d-3", "x");
    }
  }

  @Nested
  @DisplayName("원천 beta가 키워드 1위, alpha가 2위이고 별명이 알파·베타, 미수집 원천 별명이 감마일 때")
  class SourceAliases {
    private final RankingScenario scenario =
        RankingScenario.with(
            settings(
                new Sources(
                    Map.of("alpha", Set.of("알파", "Alpha"), "beta", Set.of("베타")),
                    Set.of("감마"),
                    1.0)));

    @Test
    @DisplayName("질의가 말한 원천의 점수에 1 + weight를 곱한다")
    void boostsTheNamedSource() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("알파 정산");
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId, ContextHit::score)
          .containsExactly(tuple("alpha-unit", (1.0 / 62) * 2), tuple("beta-unit", 1.0 / 61));
    }

    @Test
    @DisplayName("별명은 NFKC·소문자로 맞추고 뒤에 조사가 붙어도 일치한다")
    void matchesNormalizedAliasWithParticle() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("ＡＬＰＨＡ는 정산");
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).first().isEqualTo("alpha-unit");
    }

    @Test
    @DisplayName("낱말 가운데의 별명은 일치가 아니다")
    void ignoresAliasInsideAWord() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("오메감마 정산");
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .containsExactly("beta-unit", "alpha-unit");
    }

    @Test
    @DisplayName("미수집 원천만 말한 질의는 결과가 0건이다")
    void notIndexedSourceFindsNothing() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("감마 정산");
      // then
      assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("0건이어도 적용 계획을 싣고 재정렬은 건너뛴다")
    void notIndexedResultCarriesPlanAndSkipsRerank() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("감마 정산");
      // then
      assertThat(result.applied()).isEqualTo(new AppliedPlan(Effort.MEDIUM, Profile.AUTO, false));
      assertThat(result.rerank()).isEqualTo(new RerankStatus(RerankStatus.Kind.SKIPPED, false));
    }

    @Test
    @DisplayName("assemble도 말한 원천에 1 + weight를 곱하고, 미수집 원천 규칙은 받지 않는다")
    void assembleBoostsTheNamedSource() {
      // given
      scenario.givenTwoSources();
      // when
      var bundle = scenario.assemble("알파 정산");
      // then — assemble은 날짜 계수를 늘 붙인다: 기본 capturedAt 2026-10-01은 19일 전
      double recency = 1 + 0.1 * Math.pow(0.99, 19);
      assertThat(bundle.items())
          .extracting(BundleItem::unitId, BundleItem::rankScore)
          .containsExactlyInAnyOrder(
              tuple("alpha-unit", (1.0 / 62) * (recency * 2)),
              tuple("beta-unit", (1.0 / 61) * recency));
    }

    @Test
    @DisplayName("assemble은 미수집 원천만 말해도 0건으로 만들지 않는다")
    void assembleIgnoresTheNotIndexedRule() {
      // given
      scenario.givenTwoSources();
      // when
      var bundle = scenario.assemble("감마 정산");
      // then
      assertThat(bundle.items()).hasSize(2);
    }

    @Test
    @DisplayName("색인 원천도 함께 말하면 결과를 돌려준다")
    void indexedSourceAlongsideFindsResults() {
      // given
      scenario.givenTwoSources();
      // when
      var result = scenario.search("감마 베타 정산");
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).first().isEqualTo("beta-unit");
    }
  }
}
