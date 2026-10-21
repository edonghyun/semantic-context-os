package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CONTRACTS B2 변형 질의(TS QueryVariantsStage + ConjunctionQueryDecomposition): 접속 분해 규칙·조각 최소
 * 토큰·중복·상한.
 */
@DisplayName("변형 질의는 접속어로 나눈 2토큰 이상 조각이다(CONTRACTS B2)")
class QueryVariantsTest {
  QueryVariants variants = new QueryVariants(4);

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"결제 장애 그리고 배포 절차", "결제 장애 및 배포 절차", "결제 장애　그리고 배포 절차"})
  @DisplayName("'그리고'·'및'(JS 공백 집합으로 둘러싸인)으로 두 조각이 된다")
  void splitsOnExplicitConjunction(String query) {
    // when·then
    assertThat(variants.of(query)).containsExactly("결제 장애", "배포 절차");
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"결제 장애와 배포 절차", "결제 장애과 배포 절차"})
  @DisplayName("조사 꼬리 '와'·'과'(2글자 이상 어간)를 떼어 낸 자리에서 나눈다")
  void splitsOnJosaTail(String query) {
    // when·then
    assertThat(variants.of(query)).containsExactly("결제 장애", "배포 절차");
  }

  @Test
  @DisplayName("조사 꼬리는 마지막 토큰에는 적용되지 않는다(뒤 조각이 없다)")
  void josaOnLastTokenDoesNotSplit() {
    // when·then
    assertThat(variants.of("결제 장애와")).isEmpty();
  }

  @Test
  @DisplayName("한 글자 어간('와'·'과' 자체)은 조사 꼬리로 보지 않는다")
  void singleCharStemIsNotJosa() {
    // when·then
    assertThat(variants.of("결제 과 배포 절차")).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"결제 그리고 배포 절차", "결제 장애 그리고 배포", "결제와 배포 절차"})
  @DisplayName("조각이 2토큰 미만이면 분해하지 않는다")
  void partsNeedAtLeastTwoTokens(String query) {
    // when·then
    assertThat(variants.of(query)).isEmpty();
  }

  @Test
  @DisplayName("접속어가 없으면 변형이 없다")
  void noConjunctionNoVariants() {
    // when·then
    assertThat(variants.of("결제 장애 원인")).isEmpty();
  }

  @Test
  @DisplayName("같은 조각은 한 번만 변형이 된다")
  void dedupesAndDropsOriginal() {
    // when·then
    assertThat(variants.of("결제 장애 그리고 결제 장애")).containsExactly("결제 장애");
  }

  @Test
  @DisplayName("빈 조각('그리고'로 끝나는 자리)은 버린다")
  void blankPartsAreDropped() {
    // when·then
    assertThat(variants.of("결제 장애 그리고 배포 절차 그리고 ")).containsExactly("결제 장애", "배포 절차");
  }

  @ParameterizedTest(name = "maxVariants {0}: \"{1}\"")
  @CsvSource(
      delimiter = '|',
      value = {"1|결제 장애 그리고 배포 절차|결제 장애", "2|결제 장애 그리고 배포 절차 그리고 세션 만료|결제 장애,배포 절차"})
  @DisplayName("변형 수는 maxVariants까지다")
  void capsAtMaxVariants(int maxVariants, String query, String expected) {
    // when
    List<String> got = new QueryVariants(maxVariants).of(query);

    // then
    assertThat(got).containsExactly(expected.split(","));
  }

  @Test
  @DisplayName("세 조각 이상도 전부 변형이 된다(상한 안에서)")
  void threeParts() {
    // when·then
    assertThat(variants.of("결제 장애 그리고 배포 절차 및 세션 만료"))
        .isEqualTo(List.of("결제 장애", "배포 절차", "세션 만료"));
  }
}
