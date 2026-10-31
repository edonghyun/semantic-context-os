package io.scos.harness;

import io.scos.spi.embedding.TextGenerator;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 하네스 카세트 재생 생성기(ADR-0017): 프롬프트 sha256 → 응답(카세트 `generate`). 미스는 로그 + GenerationException("Gemini
 * API 503", 기록한 shim과 같은 결과).
 */
final class CassetteTextGenerator implements TextGenerator {
  private final Cassette cassette;

  CassetteTextGenerator(Path cassette, Path missLog, ObjectMapper json) {
    this.cassette = Cassette.section("generate", cassette, missLog, json);
  }

  @Override
  public String generate(String prompt, int maxTokens) {
    return cassette
        .lookup(prompt)
        .map(JsonNode::asString)
        .orElseThrow(
            () -> {
              cassette.recordMiss(prompt);
              return new GenerationException("Gemini API 503");
            });
  }
}
