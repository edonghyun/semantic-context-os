package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Current는 관련 결과를 최신순으로 반환한다(CONTRACTS B12)")
class CurrentProfileTest {
  private final CurrentProfileScenario scenario = new CurrentProfileScenario();

  @Nested
  @DisplayName("관련도 1위는 옛 유닛이고 2위는 새 유닛일 때")
  class OlderRanksFirst {
    @BeforeEach
    void given() {
      scenario.givenOlderRanksFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"정산", "최근 정산"})
    @DisplayName("auto로 검색하면 날짜 가산 여부와 무관하게 관련도 순서를 유지한다")
    void autoKeepsRelevanceOrder(String query) {
      // when
      var result = scenario.search(Profile.AUTO, 2, query);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("old", "new");
    }

    @Test
    @DisplayName("limit 밖의 새 유닛은 current 후보 창 안이면 반환된다")
    void includesNewerOutsideOriginalLimit() {
      // when
      var result = scenario.search(Profile.CURRENT, 1);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("new");
    }

    @Test
    @DisplayName("LOW 검색이 MEDIUM으로 상향되면 최종 결과도 최신순이다")
    void ordersEscalatedSearch() {
      // when
      var result = scenario.search(Profile.CURRENT, 3);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("new", "old");
      assertThat(result.applied()).isEqualTo(new AppliedPlan(Effort.MEDIUM, Profile.CURRENT, true));
    }

    @Test
    @DisplayName("current로 검색하면 새 유닛이 먼저 나온다")
    void currentPutsNewerFirst() {
      // when
      var result = scenario.search(Profile.CURRENT, 2);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("new", "old");
    }
  }

  @Nested
  @DisplayName("가장 최신인 유닛이 관련도 3위일 때")
  class NewestOutsideWindow {
    @BeforeEach
    void given() {
      scenario.givenNewestOutsideWindow();
    }

    @Test
    @DisplayName("limit 1로 current를 검색하면 관련 상위 2개에서만 고른다")
    void excludesNewestOutsideRelevanceWindow() {
      // when
      var result = scenario.search(Profile.CURRENT, 1);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("new");
    }
  }

  @Nested
  @DisplayName("예산에는 옛 유닛과 새 유닛만 담길 때")
  class BudgetSelectsTwo {
    @BeforeEach
    void given() {
      scenario.givenBudgetSelectsOlderAndNewer();
    }

    @Test
    @DisplayName("auto로 조립하면 날짜 가산 뒤 기존 선택과 순서를 유지한다")
    void autoPreservesSelection() {
      // when
      var result = scenario.assemble(Profile.AUTO);
      // then
      assertThat(result.items()).extracting(BundleItem::unitId).containsExactly("old", "new");
    }

    @Test
    @DisplayName("current로 조립하면 사용 토큰과 예산 초과 표시를 보존한다")
    void preservesBudgetMetadata() {
      // when
      var result = scenario.assemble(Profile.CURRENT);
      // then
      assertThat(result)
          .extracting(ContextBundle::usedTokens, ContextBundle::truncated)
          .containsExactly(500, true);
    }

    @Test
    @DisplayName("current로 조립하면 선택된 유닛만 최신순으로 나온다")
    void assemblyOrdersSelectedUnits() {
      // when
      var result = scenario.assemble(Profile.CURRENT);
      // then
      assertThat(result.items()).extracting(BundleItem::unitId).containsExactly("new", "old");
    }
  }

  @Nested
  @DisplayName("잘못된 시각과 빈 시각의 유닛이 관련도 상위에 있을 때")
  class UnknownTimes {
    @BeforeEach
    void given() {
      scenario.givenUnknownTimes();
    }

    @Test
    @DisplayName("current로 검색하면 시각 없는 유닛은 원순서대로 맨 뒤에 나온다")
    void searchPutsUnknownTimesLast() {
      // when
      var result = scenario.search(Profile.CURRENT, 4);
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .containsExactly("new", "old", "invalid", "missing");
    }

    @Test
    @DisplayName("current로 조립하면 시각 없는 유닛은 맨 뒤에 나온다")
    void assemblyPutsUnknownTimesLast() {
      // when
      var result = scenario.assemble(Profile.CURRENT);
      // then
      assertThat(result.items())
          .extracting(BundleItem::unitId)
          .containsExactly("new", "old", "invalid", "missing");
    }
  }

  @Nested
  @DisplayName("같은 시각을 오프셋이 다른 문자열로 저장했을 때")
  class SameInstant {
    @BeforeEach
    void given() {
      scenario.givenSameInstant();
    }

    @Test
    @DisplayName("current로 검색하면 관련도 원순서를 보존한다")
    void searchPreservesTies() {
      // when
      var result = scenario.search(Profile.CURRENT, 3);
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("current로 조립하면 U자 배치 뒤의 원순서를 보존한다")
    void assemblyPreservesTies() {
      // when
      var result = scenario.assemble(Profile.CURRENT);
      // then
      assertThat(result.items())
          .extracting(BundleItem::unitId)
          .containsExactly("first", "third", "second");
    }
  }

  @Test
  @DisplayName("limit 10이면 관련 상위 20개만 최신순 후보가 된다")
  void maximumMcpLimitUsesTwentyCandidates() {
    // given
    scenario.givenMoreThanTwentyCandidates();
    // when
    var result = scenario.search(Profile.CURRENT, 10, Effort.MEDIUM);
    // then
    assertThat(result.hits())
        .extracting(ContextHit::unitId)
        .containsExactly(
            "rank-20", "rank-19", "rank-18", "rank-17", "rank-16", "rank-15", "rank-14", "rank-13",
            "rank-12", "rank-11");
  }

  @Test
  @DisplayName("LOW 조립이 MEDIUM으로 상향되면 최종 결과도 최신순이다")
  void ordersEscalatedAssembly() {
    // given
    scenario.givenLowFindsOnlyInvalidatedUnits();
    // when
    var result = scenario.assemble(Profile.CURRENT);
    // then
    assertThat(result.items()).extracting(BundleItem::unitId).containsExactly("new", "old");
    assertThat(result.applied()).isEqualTo(new AppliedPlan(Effort.MEDIUM, Profile.CURRENT, true));
  }
}
