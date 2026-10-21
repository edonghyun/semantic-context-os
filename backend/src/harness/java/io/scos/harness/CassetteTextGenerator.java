package io.scos.harness;

import io.scos.engine.support.text.Text;
import io.scos.spi.embedding.TextGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 하네스 카세트 재생 생성기(ADR-0017): 프롬프트 sha256 → 응답(cassette.json `generate`). 미스는 로그 +
 * GenerationException("Gemini API 503", TS shim과 같은 결과).
 */
final class CassetteTextGenerator implements TextGenerator {
  private final ObjectMapper json;
  private final JsonNode generate;
  private final Path missLog;

  CassetteTextGenerator(Path cassette, Path missLog, ObjectMapper json) {
    this.json = json;
    try {
      this.generate = json.readTree(Files.readString(cassette)).path("generate");
    } catch (IOException e) {
      throw new IllegalStateException("cassette unreadable: " + cassette, e);
    }
    this.missLog = missLog;
  }

  @Override
  public String generate(String prompt, int maxTokens) {
    JsonNode v = generate.get(Text.sha256Hex(prompt));
    if (v == null || v.isMissingNode()) {
      if (missLog != null) {
        String line =
            "{\"kind\":\"generate\",\"sha256\":\""
                + Text.sha256Hex(prompt)
                + "\",\"text\":"
                + json.writeValueAsString(prompt)
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
      throw new GenerationException("Gemini API 503");
    }
    return v.asString();
  }
}
