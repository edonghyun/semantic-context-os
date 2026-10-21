package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * shared {@link Text} 함수를 언어 중립 결정론 fixture(harness/fixtures/p1/determinism.json, TS 실행으로 생성)와
 * 맞춘다(CONTRACTS §I). shared 몫: F-1 공백 분리·NFKC 소문자, F-2 길이, F-3 sha256. F-2는 제품 함수가 아니라 제품이 그대로 쓰는
 * JDK 길이(String.length 등)가 TS와 같다는 전제다. F-5·F-7은 각 모듈의
 * 테스트(SearchDeterminismTest·WireDeterminismTest)가 같은 fixture로 검증한다(테스트 때문에 public을 늘리지 않는다).
 */
@DisplayName("shared 텍스트 함수는 TS 결정론 fixture와 같다(CONTRACTS §I F-1–F-3)")
class TextDeterminismTest {
  static final JsonNode D = DeterminismFixture.load();

  static Stream<Named<JsonNode>> whitespaceCases() {
    return DeterminismFixture.cases(D.get("F1_whitespaceSplit"), "input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("whitespaceCases")
  @DisplayName("공백 분리는 TS `\\s`(NBSP·U+3000·탭·개행 포함)와 같은 토큰을 낸다")
  void whitespaceSplitMatchesTs(JsonNode c) {
    // given
    List<String> want = new ArrayList<>();
    c.get("tokens").forEach(t -> want.add(t.asString()));

    // when
    List<String> tokens = Text.splitWhitespace(c.get("input").asString());

    // then
    assertThat(tokens).isEqualTo(want);
  }

  static Stream<Named<JsonNode>> nfkcCases() {
    return DeterminismFixture.cases(D.get("F1_nfkcLower"), "input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nfkcCases")
  @DisplayName("NFKC + 소문자는 TS normalize('NFKC').toLowerCase()와 같다")
  void nfkcLowerMatchesTs(JsonNode c) {
    // when
    String lowered = Text.nfkcLower(c.get("input").asString());

    // then
    assertThat(lowered).isEqualTo(c.get("output").asString());
  }

  static Stream<Named<JsonNode>> lengthCases() {
    return DeterminismFixture.cases(D.get("F2_lengths"), "input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lengthCases")
  @DisplayName("제품이 쓰는 JDK 길이(UTF-16·코드포인트·UTF-8 바이트)가 TS와 같다 — 전제")
  void lengthsMatchTs(JsonNode c) {
    // given
    String s = c.get("input").asString();

    // when·then
    assertThat(s.length()).as("utf16").isEqualTo(c.get("utf16").asInt());
    assertThat(s.codePointCount(0, s.length()))
        .as("codePoints")
        .isEqualTo(c.get("codePoints").asInt());
    assertThat(s.getBytes(StandardCharsets.UTF_8).length)
        .as("utf8Bytes")
        .isEqualTo(c.get("utf8Bytes").asInt());
  }

  static Stream<Named<JsonNode>> sha256Cases() {
    return DeterminismFixture.cases(D.get("F3_sha256"), "input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sha256Cases")
  @DisplayName("sha256(utf8) hex가 TS와 같다")
  void sha256MatchesTs(JsonNode c) {
    // when
    String hex = Text.sha256Hex(c.get("input").asString());

    // then
    assertThat(hex).isEqualTo(c.get("sha256").asString());
  }
}
