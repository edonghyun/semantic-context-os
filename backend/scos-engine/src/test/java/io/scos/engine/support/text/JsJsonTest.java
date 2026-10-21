package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 커넥터 sourceVersion(Jira·Tiro)의 바탕인 JSON.stringify 동등 직렬화. 기대값은 Node 22
 * `JSON.stringify(JSON.parse(입력))`의 출력이다(수 표기는 ECMAScript Number::toString).
 */
@DisplayName("JSON 직렬화는 JS JSON.stringify와 같은 문자열을 낸다")
class JsJsonTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static String stringify(String json) {
    return JsJson.stringify(JSON.readTree(json));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "0 | 0",
        "-0.0 | 0",
        "7 | 7",
        "-12 | -12",
        "2.0 | 2",
        "0.1 | 0.1",
        "0.5 | 0.5",
        "-3.5 | -3.5",
        "123.456 | 123.456",
        "1e21 | 1e+21",
        "1.2345e25 | 1.2345e+25",
        "1e20 | 100000000000000000000",
        "123456789012345678 | 123456789012345680",
        "9007199254740991 | 9007199254740991",
        "-9007199254740991 | -9007199254740991",
        "9007199254740993 | 9007199254740992",
        "1e-6 | 0.000001",
        "1.5e-6 | 0.0000015",
        "1e-7 | 1e-7",
        "-2.5e-8 | -2.5e-8",
        "1234.5e-2 | 12.345",
        "5e-324 | 5e-324",
        "-5e-324 | -5e-324",
        "1e-323 | 1e-323",
        "1.5e-323 | 1.5e-323",
        "1e-310 | 1e-310"
      })
  @DisplayName("수는 JS Number#toString 표기로 쓴다")
  void writesNumbersLikeJs(String input, String expected) {
    // when
    String written = stringify(input);

    // then
    assertThat(written).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'\"따옴표 \\\"인용\\\"\"' | '\"따옴표 \\\"인용\\\"\"'",
        "'\"역슬래시 \\\\\"' | '\"역슬래시 \\\\\"'",
        "'\"\\b\\f\\n\\r\\t\"' | '\"\\b\\f\\n\\r\\t\"'",
        "'\"\\u0001\\u001F\"' | '\"\\u0001\\u001f\"'",
        "'\"/ 🚀 é\"' | '\"/ 🚀 é\"'",
        "'\"\\ud800 홀로\"' | '\"\\ud800 홀로\"'",
        "'\"끝 \\udc00\"' | '\"끝 \\udc00\"'",
        "'\"\\udc00\\ud800\"' | '\"\\udc00\\ud800\"'",
        "'\"\\u007f\"' | '\"\u007f\"'"
      })
  @DisplayName("문자열은 JSON.stringify 이스케이프로 쓴다")
  void escapesStringsLikeJs(String input, String expected) {
    // when
    String written = stringify(input);

    // then
    assertThat(written).isEqualTo(expected);
  }

  @Test
  @DisplayName("인덱스가 아닌 객체 키는 받은 순서대로 공백 없이 쓴다")
  void keepsKeyOrderWithoutSpaces() {
    // given
    String json = "{ \"z\": [1, true, null, {\"a\": false}], \"a\": {} , \"m\": [] }";

    // when
    String written = stringify(json);

    // then
    assertThat(written).isEqualTo("{\"z\":[1,true,null,{\"a\":false}],\"a\":{},\"m\":[]}");
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'{\"b\":1,\"10\":2,\"a\":{\"z\":0,\"2\":1,\"1\":2}}' | '{\"10\":2,\"b\":1,\"a\":{\"1\":2,\"2\":1,\"z\":0}}'",
        "'{\"4294967294\":1,\"4294967295\":2,\"01\":3,\"-1\":4,\"1.5\":5,\"0\":6}' | '{\"0\":6,\"4294967294\":1,\"4294967295\":2,\"01\":3,\"-1\":4,\"1.5\":5}'"
      })
  @DisplayName("배열 인덱스 모양의 키는 JS 객체처럼 오름차순으로 먼저 쓴다")
  void writesArrayIndexKeysFirstLikeJs(String input, String expected) {
    // when: 배열 인덱스 = 0 또는 0으로 시작하지 않는 2^32 − 2 이하의 정수 표기. "01"·"-1"·"1.5"·2^32 − 1은 넣은 순서
    String written = stringify(input);

    // then
    assertThat(written).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "'{\"a\":1,\"b\":[1,{\"c\":[]},{}],\"10\":\"x\",\"d\":{\"e\":null}}' | '{\n  \"10\": \"x\",\n  \"a\": 1,\n  \"b\": [\n    1,\n    {\n      \"c\": []\n    },\n    {}\n  ],\n  \"d\": {\n    \"e\": null\n  }\n}'",
        "'[]' | '[]'",
        "'{}' | '{}'",
        "'\"s\"' | '\"s\"'",
        "'[[],[1]]' | '[\n  [],\n  [\n    1\n  ]\n]'"
      })
  @DisplayName("들여쓰기 2칸 직렬화는 JSON.stringify(값, null, 2)와 같다")
  void writesPrettyLikeJs(String input, String expected) {
    // when
    String written = JsJson.pretty(JSON.readTree(input));

    // then: CsvSource의 \n은 줄바꿈 두 글자라 실제 줄바꿈으로 바꿔 비교한다
    assertThat(written).isEqualTo(expected.replace("\\n", "\n"));
  }
}
