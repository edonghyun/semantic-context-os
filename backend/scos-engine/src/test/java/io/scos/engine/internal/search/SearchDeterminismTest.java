package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.scos.engine.support.text.DeterminismFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** search 모듈의 결정론(CONTRACTS F-5·F-7): 같은 패키지라 package-private 멤버를 그대로 쓴다. */
@DisplayName("검색의 동점 정렬·날짜 가중·RRF는 TS fixture와 같다(CONTRACTS F-5·F-7)")
class SearchDeterminismTest {
  static final JsonNode D = DeterminismFixture.load();

  @Test
  @DisplayName("동점 비교자는 ASCII 식별자 구간에서 TS localeCompare와 같다")
  void tieComparatorAsciiSubset() {
    // given: Collator ROOT. 전체 순서는 TS가 프로세스 로케일에 의존해(D4) 비교하지 않는다
    JsonNode s = D.get("F5_sorts");
    List<String> input = new ArrayList<>();
    s.get("input").forEach(x -> input.add(x.asString()));
    List<String> wantLocale = new ArrayList<>();
    s.get("localeCompareSort").forEach(x -> wantLocale.add(x.asString()));
    List<String> asciiInput =
        input.stream().filter(x -> x.chars().allMatch(ch -> ch < 128)).toList();
    if (asciiInput.size() < 2)
      throw new IllegalStateException(
          "given 준비 실패: fixture에 ASCII 사례가 둘 이상 있어야 한다 — " + asciiInput);

    // when
    List<String> locale = new ArrayList<>(asciiInput);
    locale.sort(RelatedDocuments.LOCALE);

    // then
    assertThat(locale).isEqualTo(wantLocale.stream().filter(asciiInput::contains).toList());
    // 한글·아스트랄 문자를 포함한 전체 순서는 fixture 생성 머신의 ICU 로케일(ko-KR)에 묶여 단언하지 않는다 — CONTRACTS D4
  }

  static Stream<Named<JsonNode>> powCases() {
    return DeterminismFixture.cases(D.get("F7_pow"), "days");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("powCases")
  @DisplayName("날짜 가중 1+0.1·0.99^days는 TS Math.pow와 상대 오차 1e-12 안")
  void freshnessFactorMatchesTs(JsonNode c) {
    // given
    double expected = c.get("value").asDouble();

    // when
    double got = SearchRanker.freshnessFactor(c.get("days").asDouble());

    // then
    assertThat(Math.abs(got - expected) / expected).isLessThanOrEqualTo(1e-12);
  }

  static Stream<Named<JsonNode>> rrfCases() {
    return DeterminismFixture.cases(D.get("F7_rrf").get("values"), "value");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rrfCases")
  @DisplayName("RRF 항 weight/(k+rank+1)은 TS와 같다(k=60)")
  void rrfTermMatchesTs(JsonNode c) {
    // given
    int k = D.get("F7_rrf").get("k").asInt();

    // when
    double got = Rrf.term(c.get("weight").asDouble(), k, c.get("rank").asInt());

    // then
    assertThat(got).isCloseTo(c.get("value").asDouble(), withinPercentage(1e-10));
  }
}
