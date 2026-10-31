package io.scos.client.source.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("합성 트랜스크립트는 실제 줄 형식을 따른다(E1)")
class TranscriptFixtureTest {
  private List<JsonNode> lines() throws IOException {
    try (var in = getClass().getResourceAsStream("/transcripts/turns.jsonl")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(ClientJson.MAPPER::readTree)
          .toList();
    }
  }

  @Test
  @DisplayName("ai-title에는 timestamp가 없다")
  void titlesHaveNoTimestamp() throws IOException {
    // when
    List<JsonNode> titles =
        lines().stream().filter(line -> line.path("type").asString().equals("ai-title")).toList();

    // then
    assertThat(titles).hasSize(2).allSatisfy(line -> assertThat(line.has("timestamp")).isFalse());
  }

  @Test
  @DisplayName("사용자 메시지는 블록 배열이다")
  void userContentIsBlocks() throws IOException {
    // when
    List<JsonNode> users =
        lines().stream().filter(line -> line.path("type").asString().equals("user")).toList();

    // then
    assertThat(users)
        .hasSize(9)
        .allSatisfy(line -> assertThat(line.path("message").path("content").isArray()).isTrue());
  }

  @Test
  @DisplayName("본문이 아닌 실제 줄 종류도 합성 픽스처에 들어 있다")
  void includesNamedNonProjectedKinds() throws IOException {
    // when
    List<String> kinds =
        lines().stream()
            .map(line -> line.path("type").asString())
            .filter(kind -> !List.of("user", "assistant", "ai-title").contains(kind))
            .toList();

    // then
    assertThat(kinds)
        .containsExactly(
            "attachment",
            "api-request-blob",
            "last-prompt",
            "mode",
            "system",
            "file-history-snapshot");
  }
}
