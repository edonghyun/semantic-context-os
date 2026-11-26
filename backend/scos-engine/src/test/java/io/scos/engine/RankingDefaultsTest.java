package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 순위 설정을 주지 않은 엔진과 {@link RankingSettings#DEFAULT}를 넘긴 엔진의 점수와 순서(사내 인스턴스 불변). 키워드 전용 RRF 1위 =
 * 1/61, 2위 = 1/62. 날짜 계수는 1 + 0.1·0.99^days이고, 시간 낱말이 있는 auto 질의에만 붙는다(CONTRACTS B8·B13).
 */
@DisplayName("기본 순위는 RRF × 날짜 계수(시간 낱말일 때) → 문서당 2개다")
class RankingDefaultsTest {
  static Stream<Named<Supplier<RankingScenario>>> engines() {
    return Stream.of(
        Named.of("순위 설정 없음", RankingScenario::new),
        Named.of("ranking(DEFAULT)", () -> RankingScenario.with(RankingSettings.DEFAULT)));
  }

  @ParameterizedTest
  @MethodSource("engines")
  @DisplayName("시간 낱말이 없으면 점수는 RRF 그대로다")
  void plainQueryKeepsRrfScores(Supplier<RankingScenario> engine) {
    // given
    RankingScenario scenario = engine.get();
    scenario.givenOlderMatchesMore();
    // when
    var result = scenario.search("정산");
    // then
    assertThat(result.hits())
        .extracting(ContextHit::unitId, ContextHit::score)
        .containsExactly(tuple("older", 1.0 / 61), tuple("newer", 1.0 / 62));
  }

  @ParameterizedTest
  @MethodSource("engines")
  @DisplayName("시간 낱말이 있으면 1 + 0.1·0.99^days를 곱한다")
  void markedQueryMultipliesTheDateFactor(Supplier<RankingScenario> engine) {
    // given
    RankingScenario scenario = engine.get();
    scenario.givenOlderMatchesMore();
    // when
    var result = scenario.search("최근 정산");
    // then
    assertThat(result.hits())
        .extracting(ContextHit::unitId, ContextHit::score)
        .containsExactly(
            tuple("older", (1.0 / 61) * (1 + 0.1 * Math.pow(0.99, 10))),
            tuple("newer", (1.0 / 62) * 1.1));
  }

  @ParameterizedTest
  @MethodSource("engines")
  @DisplayName("한 문서의 조각은 앞에서 2개까지만 남는다")
  void keepsTwoUnitsPerDocument(Supplier<RankingScenario> engine) {
    // given
    RankingScenario scenario = engine.get();
    scenario.givenOneDocumentFillsTheTop();
    // when
    var result = scenario.search("정산");
    // then
    assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("d-1", "d-2", "x");
  }

  @ParameterizedTest
  @MethodSource("engines")
  @DisplayName("current는 limit의 두 배인 관련 상위 창 안에서 최신순으로 고른다")
  void currentChoosesWithinTwiceTheLimit(Supplier<RankingScenario> engine) {
    // given
    RankingScenario scenario = engine.get();
    scenario.givenOlderMatchesMore();
    // when
    var result = scenario.search("정산", Profile.CURRENT, 1);
    // then
    assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("newer");
  }
}
