package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Drive 어댑터가 TS 값 비교에 쓰는 JS 의미(`String(v)`·`Number(v)`·참 값·`Number.isInteger`). 기대값은 Node에서 같은 식을
 * 평가한 값이다. 표의 JSON은 응답·체크포인트 값 하나다.
 */
@DisplayName("Drive 값 비교는 JS String·Number·참 값 의미를 따른다")
class JsValuesTest {
  static JsonNode json(String text) {
    return GoogleDriveFixtures.JSON.readTree(text);
  }

  @ParameterizedTest(name = "String({0}) = \"{1}\"")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"v\"|v",
        "7|7",
        "1e21|1e+21",
        "1.50|1.5",
        "true|true",
        "false|false",
        "null|null",
        "[\"a\",null,[1,2]]|a,,1,2",
        "[]|''",
        "{}|[object Object]"
      })
  @DisplayName("String(값)은 JS와 같다")
  void stringLikeJs(String value, String expected) {
    // when·then
    assertThat(JsValues.string(json(value))).isEqualTo(expected);
  }

  @Test
  @DisplayName("없는 값의 String은 undefined다")
  void absentIsUndefined() {
    // when·then
    assertThat(JsValues.string(null)).isEqualTo("undefined");
  }

  @ParameterizedTest(name = "Number({0}) = {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"12\"|12",
        "\" 5 \"|5",
        "\"\"|0",
        "\"0x10\"|16",
        "\"1e3\"|1000",
        "\"-2.5\"|-2.5",
        "\"\\u00a0 7\\ufeff\"|7",
        "3|3",
        "null|0",
        "true|1",
        "false|0",
        "[\"8\"]|8",
        "\"0b11\"|3",
        "\"0o17\"|15"
      })
  @DisplayName("수로 읽히는 값의 Number는 JS와 같다")
  void numberLikeJs(String value, double expected) {
    // when·then
    assertThat(JsValues.number(json(value))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "Number({0}) = NaN")
  @ValueSource(strings = {"\"abc\"", "\"12px\"", "{}", "\"\\u00857\"", "\"0B2\"", "\"0b\""})
  @DisplayName("수로 읽히지 않는 값의 Number는 NaN이다")
  void numberIsNaN(String value) {
    // when·then
    assertThat(JsValues.number(json(value))).isNaN();
  }

  @ParameterizedTest(name = "Number({0}) = ±∞")
  @ValueSource(strings = {"\"Infinity\"", "\"+Infinity\"", "\"-Infinity\""})
  @DisplayName("Infinity 문자열의 Number는 무한대다")
  void numberIsInfinite(String value) {
    // when·then
    assertThat(JsValues.number(json(value))).isInfinite();
  }

  @Test
  @DisplayName("없는 값의 Number는 NaN이다")
  void absentNumberIsNaN() {
    // when·then
    assertThat(JsValues.number(null)).isNaN();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"x\"|true",
        "\"\"|false",
        "0|false",
        "2|true",
        "null|false",
        "false|false",
        "true|true",
        "{}|true",
        "[]|true"
      })
  @DisplayName("참 값 판정은 JS와 같다")
  void truthyLikeJs(String value, boolean expected) {
    // when·then
    assertThat(JsValues.truthy(json(value))).isEqualTo(expected);
  }

  @Test
  @DisplayName("없는 값은 거짓이다")
  void absentIsFalsy() {
    // when·then
    assertThat(JsValues.truthy(null)).isFalse();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {"1|true", "1.0|true", "1e21|true", "1.5|false", "\"1\"|false"})
  @DisplayName("Number.isInteger는 JS와 같다")
  void isIntegerLikeJs(String value, boolean expected) {
    // when·then
    assertThat(JsValues.isInteger(json(value))).isEqualTo(expected);
  }
}
