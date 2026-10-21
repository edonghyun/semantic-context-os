package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.JiraAssembly;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira 값 평문화(TS renderJiraValue — ADF와 일반 값, F5 T5.4): 표본마다 TS 출력과 바이트 단위로 같다. 골든은
 * harness/fixtures/jira/expected-adf.json(TS JiraProjector로 뽑은 출력)이다 — ADF 노드 약 20종(text·링크
 * 표식·hardBreak·mention·
 * emoji·inlineCard·blockCard·paragraph·heading·목록·표·codeBlock·panel·blockquote·rule·media·doc)과 일반
 * 값·객체 대체 규칙.
 */
@DisplayName("Jira 값 평문화는 TS renderJiraValue와 바이트 단위로 같다(F5 T5.4)")
class JiraValueRendererTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR = JiraAssembly.FIXTURES;

  static JsonNode golden() {
    try {
      return JSON.readTree(Files.readString(DIR.resolve("expected-adf.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Stream<Arguments> rawCases() {
    return StreamSupport.stream(golden().get("rawCases").spliterator(), false)
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    c.get("json").asString(),
                    c.get("rendered").asString()));
  }

  static Stream<Arguments> tsCases() {
    JsonNode root = golden();
    return StreamSupport.stream(root.get("cases").spliterator(), false)
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(), c.get("value"), c.get("rendered").asString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tsCases")
  @DisplayName("평문화 결과가 TS와 바이트 단위로 같다")
  void rendersLikeTs(String label, JsonNode value, String rendered) {
    // when·then
    assertThat(JiraValueRenderer.render(value)).isEqualTo(rendered);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rawCases")
  @DisplayName("응답 원문의 키 순서와 무관하게 JS 객체 순회 순서로 쓴다")
  void walksKeysLikeJsObjects(String label, String json, String rendered) {
    // when·then
    assertThat(JiraValueRenderer.render(JSON.readTree(json))).isEqualTo(rendered);
  }
}
