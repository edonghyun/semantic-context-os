package io.scos.harness;

import io.scos.engine.support.text.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 카세트 한 구역(harness/fixtures/p1/cassette.json의 `embed`·`generate`): 입력 텍스트의 sha256 → 기록된 값. 미스는 미스
 * 로그(JSONL, 없으면 쓰지 않는다)에 {@code {"kind":…,"sha256":…,"text":…}} 한 줄로 남긴다.
 */
final class Cassette {
  private final String kind;
  private final JsonNode section;
  private final Path missLog;
  private final ObjectMapper json;

  private Cassette(String kind, JsonNode section, Path missLog, ObjectMapper json) {
    this.kind = kind;
    this.section = section;
    this.missLog = missLog;
    this.json = json;
  }

  static Cassette section(String kind, Path cassette, Path missLog, ObjectMapper json) {
    try {
      return new Cassette(
          kind, json.readTree(Files.readString(cassette)).path(kind), missLog, json);
    } catch (IOException e) {
      throw new IllegalStateException("cassette unreadable: " + cassette, e);
    }
  }

  /** 기록된 값. 없으면 empty — 미스 기록은 {@link #recordMiss}로 따로 한다. */
  Optional<JsonNode> lookup(String text) {
    JsonNode v = section.get(Text.sha256Hex(text));
    return v == null || v.isMissingNode() ? Optional.empty() : Optional.of(v);
  }

  void recordMiss(String text) {
    if (missLog == null) return;
    String line =
        "{\"kind\":\""
            + kind
            + "\",\"sha256\":\""
            + Text.sha256Hex(text)
            + "\",\"text\":"
            + json.writeValueAsString(text)
            + "}\n";
    try {
      Files.writeString(
          missLog,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("miss log unwritable: " + missLog, e);
    }
  }
}
