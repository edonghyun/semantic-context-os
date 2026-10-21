package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drive 투영기가 쓰는 JS 의미(참 값·`===`·`String(v)`·`Number(v)`·trim). 기대값은 Node에서 같은 식을 평가한 값이다. */
@DisplayName("Drive 투영기의 JS 의미(참 값·===·String·Number·trim)는 Node와 같다")
class JsTextTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"x\"|true",
        "\"\"|false",
        "0|false",
        "2|true",
        "-1.5|true",
        "null|false",
        "false|false",
        "true|true",
        "{}|true",
        "[]|true"
      })
  @DisplayName("참 값 판정은 JS와 같다")
  void truthyLikeJs(String value, boolean expected) {
    // when·then
    assertThat(JsText.truthy(json(value))).isEqualTo(expected);
  }

  @Test
  @DisplayName("없는 값은 거짓이다")
  void absentIsFalsy() {
    // when·then
    assertThat(JsText.truthy(null)).isFalse();
  }

  @ParameterizedTest(name = "{0} === {1} → {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "null|null|true",
        "\"a\"|\"a\"|true",
        "\"a\"|\"b\"|false",
        "7|7.0|true",
        "7|8|false",
        "\"7\"|7|false",
        "true|true|true",
        "true|false|false",
        "null|\"a\"|false",
        "{}|{}|false",
        "[]|[]|false"
      })
  @DisplayName("엄격 같음은 JS와 같다")
  void strictEqualsLikeJs(String a, String b, boolean expected) {
    // when·then
    assertThat(JsText.strictEquals(json(a), json(b))).isEqualTo(expected);
  }

  @Test
  @DisplayName("둘 다 없으면 같다")
  void absentEqualsAbsent() {
    // when·then
    assertThat(JsText.strictEquals(null, null)).isTrue();
  }

  @Test
  @DisplayName("한쪽만 없으면 다르다")
  void absentDiffersFromNull() {
    // when·then
    assertThat(JsText.strictEquals(null, json("null"))).isFalse();
  }

  @ParameterizedTest(name = "String({0}) = \"{1}\"")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"v\"|v",
        "7|7",
        "1e21|1e+21",
        "true|true",
        "null|null",
        "[\"a\",null,2]|a,,2",
        "{}|[object Object]"
      })
  @DisplayName("String(값)은 JS와 같다")
  void stringLikeJs(String value, String expected) {
    // when·then
    assertThat(JsText.string(json(value))).isEqualTo(expected);
  }

  @Test
  @DisplayName("없는 값의 String은 undefined다")
  void absentStringIsUndefined() {
    // when·then
    assertThat(JsText.string(null)).isEqualTo("undefined");
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"12\"|12",
        "\" 5 \"|5",
        "\"\"|0",
        "\"0x10\"|16",
        "3|3",
        "null|0",
        "true|1",
        "false|0",
        "[\"8\"]|8"
      })
  @DisplayName("Number(값)은 JS와 같다")
  void toNumberLikeJs(String value, double expected) {
    // when·then
    assertThat(JsText.toNumber(json(value))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {"\"abc\"", "{}"})
  @DisplayName("수로 읽히지 않는 값의 Number는 NaN이다")
  void toNumberIsNaN(String value) {
    // when·then
    assertThat(JsText.toNumber(json(value))).isNaN();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {"\"Infinity\"|Infinity", "\"-Infinity\"|-Infinity"})
  @DisplayName("무한대 수 표기는 JS와 같다")
  void infiniteNumbers(String value, String expected) {
    // when·then
    assertThat(JsText.number(JsText.toNumber(json(value)))).isEqualTo(expected);
  }

  @Test
  @DisplayName("NaN 수 표기는 NaN이다")
  void nanNumber() {
    // when·then
    assertThat(JsText.number(Double.NaN)).isEqualTo("NaN");
  }

  @Test
  @DisplayName("trim은 JS 공백(NBSP·BOM·U+2028)을 떼고 NEL은 남긴다")
  void trimsJsWhitespace() {
    // when·then
    assertThat(JsText.trim("\u00A0\uFEFF a \u2028\u0085")).isEqualTo("a \u2028\u0085");
  }

  @Test
  @DisplayName("없는 값의 Number는 NaN이다")
  void absentNumberIsNaN() {
    // when·then
    assertThat(JsText.toNumber(null)).isNaN();
  }
}
