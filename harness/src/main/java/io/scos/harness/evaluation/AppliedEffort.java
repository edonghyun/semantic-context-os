package io.scos.harness.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import java.util.Optional;

/** search_context 응답의 {@code applied}(서버가 실제로 쓴 강도와 상향 여부). 구 서버 응답에는 없다. */
public record AppliedEffort(String effort, boolean escalated) {
  public static Optional<AppliedEffort> parse(JsonNode result) {
    JsonNode text = result == null ? null : result.path("content").path(0).path("text");
    if (text == null || !text.isTextual()) return Optional.empty();
    try {
      JsonNode applied = JsonRpcStdioClient.mapper().readTree(text.asText()).path("applied");
      if (!applied.path("effort").isTextual()) return Optional.empty();
      return Optional.of(
          new AppliedEffort(applied.get("effort").asText(), applied.path("escalated").asBoolean()));
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }
}
