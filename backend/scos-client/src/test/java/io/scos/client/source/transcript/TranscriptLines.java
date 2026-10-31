package io.scos.client.source.transcript;

import io.scos.client.ClientJson;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/** 합성 내용에 Claude Code의 메시지 봉투·블록 구조를 붙인다. 실제 사용자 기록은 읽지 않는다. */
public final class TranscriptLines {
  private TranscriptLines() {}

  public static Builder user(String text) {
    return message("user", List.of(Map.of("type", "text", "text", text)));
  }

  public static Builder assistant(String text) {
    return message("assistant", List.of(Map.of("type", "text", "text", text)));
  }

  public static Builder aiTitle(String title) {
    return new Builder(
        ClientJson.MAPPER
            .createObjectNode()
            .put("type", "ai-title")
            .put("sessionId", "s1")
            .put("aiTitle", title));
  }

  public static Builder toolUse(String name, Map<String, ?> input) {
    return message(
        "assistant",
        List.of(Map.of("type", "tool_use", "id", "tool-1", "name", name, "input", input)));
  }

  public static Builder toolResult(String text) {
    return message(
        "user", List.of(Map.of("type", "tool_result", "tool_use_id", "tool-1", "content", text)));
  }

  public static Builder attachment(String text) {
    return new Builder(envelope("attachment"))
        .with(
            "attachment",
            Map.of("type", "file", "filename", "/workspace/project/example.txt", "content", text));
  }

  public static Builder apiRequestBlob(String text) {
    return new Builder(envelope("api-request-blob"))
        .with("request", Map.of("messages", List.of(Map.of("role", "user", "content", text))));
  }

  public static Builder lastPrompt(String text) {
    return new Builder(envelope("last-prompt")).with("lastPrompt", text);
  }

  public static Builder mode(String mode) {
    return new Builder(envelope("mode")).with("mode", mode);
  }

  public static Builder system(String text) {
    return new Builder(envelope("system")).with("subtype", "api-request").with("content", text);
  }

  public static Builder fileHistorySnapshot() {
    return new Builder(ClientJson.MAPPER.createObjectNode().put("type", "file-history-snapshot"))
        .with("messageId", "00000000-0000-4000-8000-000000000001")
        .with(
            "snapshot",
            Map.of(
                "messageId",
                "00000000-0000-4000-8000-000000000001",
                "trackedFileBackups",
                Map.of(),
                "timestamp",
                "2026-10-28T00:00:00Z"))
        .with("isSnapshotUpdate", false);
  }

  private static Builder message(String role, List<Map<String, ?>> blocks) {
    return new Builder(envelope(role)).with("message", Map.of("role", role, "content", blocks));
  }

  private static ObjectNode envelope(String type) {
    return ClientJson.MAPPER
        .createObjectNode()
        .put("type", type)
        .put("uuid", "00000000-0000-4000-8000-000000000001")
        .putNull("parentUuid")
        .put("sessionId", "s1")
        .put("timestamp", "2026-10-28T00:00:00Z")
        .put("cwd", "/workspace/project")
        .put("gitBranch", "main")
        .put("version", "2.1.0")
        .put("isSidechain", false)
        .put("userType", "external");
  }

  public static final class Builder {
    private final ObjectNode line;

    private Builder(ObjectNode line) {
      this.line = line;
    }

    public Builder timestamp(String timestamp) {
      return with("timestamp", timestamp);
    }

    public Builder cwd(String cwd) {
      return with("cwd", cwd);
    }

    public Builder blocks(List<Map<String, ?>> blocks) {
      ((ObjectNode) line.path("message")).set("content", ClientJson.MAPPER.valueToTree(blocks));
      return this;
    }

    public Builder answers(Map<String, ?> answers) {
      return with("toolUseResult", Map.of("answers", answers));
    }

    public Builder with(String name, Object value) {
      line.set(name, ClientJson.MAPPER.valueToTree(value));
      return this;
    }

    public String build() {
      return line.toString();
    }
  }
}
