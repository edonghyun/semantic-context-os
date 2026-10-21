package io.scos.server;

import io.scos.spi.connector.SourceConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * `source add --config`의 값(ADR-0022 §5): TS와 같은 설정 파일 경로(`{"schemaVersion":n,"value":…}`, TS
 * readSourceConfiguration). 옛 Java 형식의 인라인 값 JSON(`{`로 시작)은 받지 않는다 — 등록 형식은 TS와 하나다(§2가 접두사 없는 ref를
 * 기각한 것과 같은 판단). 지킬 운영 등록이 없고, 두 형식은 `{` 휴리스틱으로만 구분됐다.
 */
final class SourceConfigInput {
  static final String INLINE_REJECTED =
      "--config takes a source configuration file path ({\"schemaVersion\":1,\"value\":…}); inline JSON is not accepted";

  private SourceConfigInput() {}

  static SourceConfig load(ObjectMapper json, String raw) {
    if (raw.strip().startsWith("{")) throw new IllegalArgumentException(INLINE_REJECTED);
    return readFile(json, Path.of(raw));
  }

  private static SourceConfig readFile(ObjectMapper json, Path path) {
    String contents;
    try {
      contents = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("Source configuration file could not be read: " + path);
    }
    IllegalArgumentException invalid =
        new IllegalArgumentException("Invalid source configuration file: " + path);
    JsonNode root;
    try {
      root = json.readTree(contents);
    } catch (JacksonException e) {
      throw invalid;
    }
    JsonNode version = root.get("schemaVersion");
    if (!root.isObject()
        || version == null
        || !version.isIntegralNumber()
        || !version.canConvertToInt()
        || version.intValue() <= 0
        || !root.has("value")) throw invalid;
    return new SourceConfig(version.intValue(), json.writeValueAsString(root.get("value")));
  }
}
