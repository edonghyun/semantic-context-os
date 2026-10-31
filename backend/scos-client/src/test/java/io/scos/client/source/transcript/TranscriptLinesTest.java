package io.scos.client.source.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("트랜스크립트 빌더는 실제 메시지 형식을 만든다(D3, E1)")
class TranscriptLinesTest {
  @Test
  @DisplayName("사용자 줄의 기본값은 공통 봉투와 텍스트 블록이다")
  void userDefaults() {
    // when
    JsonNode line = ClientJson.MAPPER.readTree(TranscriptLines.user("질문").build());

    // then
    assertThat(line)
        .isEqualTo(
            ClientJson.MAPPER.readTree(
                """
        {"type":"user","uuid":"00000000-0000-4000-8000-000000000001",
         "parentUuid":null,"sessionId":"s1","timestamp":"2026-10-28T00:00:00Z",
         "cwd":"/workspace/project","gitBranch":"main","version":"2.1.0",
         "isSidechain":false,"userType":"external",
         "message":{"role":"user","content":[{"type":"text","text":"질문"}]}}
        """));
  }

  @Test
  @DisplayName("ai-title 기본값에는 timestamp가 없다")
  void titleHasNoTimestamp() {
    // when
    JsonNode line = ClientJson.MAPPER.readTree(TranscriptLines.aiTitle("합성 제목").build());

    // then
    assertThat(line)
        .isEqualTo(
            ClientJson.MAPPER.readTree(
                """
        {"type":"ai-title","sessionId":"s1","aiTitle":"합성 제목"}
        """));
  }

  @Test
  @DisplayName("문자열의 따옴표와 역슬래시와 개행을 보존한다")
  void escapesText() {
    // when
    JsonNode line = ClientJson.MAPPER.readTree(TranscriptLines.user("\"인용\" \\ 경로\n다음 줄").build());

    // then
    assertThat(line.path("message").path("content").get(0).path("text").asString())
        .isEqualTo("\"인용\" \\ 경로\n다음 줄");
  }

  @Test
  @DisplayName("어시스턴트 메시지는 텍스트 블록 배열이다")
  void assistantContentIsBlocks() {
    // when
    JsonNode line = ClientJson.MAPPER.readTree(TranscriptLines.assistant("답변").build());

    // then
    assertThat(line.path("message"))
        .isEqualTo(
            ClientJson.MAPPER.readTree(
                """
        {"role":"assistant","content":[{"type":"text","text":"답변"}]}
        """));
  }

  @Test
  @DisplayName("도구 호출은 입력 객체를 가진 어시스턴트 블록이다")
  void toolUseContentIsBlocks() {
    // when
    JsonNode line =
        ClientJson.MAPPER.readTree(
            TranscriptLines.toolUse("Bash", Map.of("command", "pwd")).build());

    // then
    assertThat(line.path("message"))
        .isEqualTo(
            ClientJson.MAPPER.readTree(
                """
        {"role":"assistant","content":[{"type":"tool_use","id":"tool-1",
          "name":"Bash","input":{"command":"pwd"}}]}
        """));
  }
}
