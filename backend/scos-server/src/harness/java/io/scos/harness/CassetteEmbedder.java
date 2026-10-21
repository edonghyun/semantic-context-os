package io.scos.harness;

import io.scos.spi.embedding.Embedder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 하네스 카세트 재생 임베더(ADR-0017, 제품 JAR 밖): 텍스트 sha256 → 벡터(harness/fixtures/p1/cassette.json, TS shim과
 * 같은 키). 미스는 미스 로그(JSONL)에 쓰고 {@link Embedder.EmbedderException}("Gemini embeddings 503" — TS가 shim
 * 503에 내는 메시지와 같다)을 던진다. 실 네트워크 없음.
 */
final class CassetteEmbedder implements Embedder {
  private final ObjectMapper json;
  private final JsonNode embed;
  private final Path missLog;

  CassetteEmbedder(Path cassette, Path missLog, ObjectMapper json) {
    this.json = json;
    try {
      this.embed = json.readTree(Files.readString(cassette)).path("embed");
    } catch (IOException e) {
      throw new IllegalStateException("cassette unreadable: " + cassette, e);
    }
    this.missLog = missLog;
  }

  @Override
  public int dim() {
    return 768;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    List<double[]> out = new ArrayList<>(texts.size());
    List<String> missing = new ArrayList<>();
    for (String t : texts) {
      JsonNode v = embed.get(io.scos.engine.support.text.Text.sha256Hex(t));
      if (v == null || v.isMissingNode()) {
        missing.add(t);
        continue;
      }
      double[] d = new double[v.size()];
      for (int i = 0; i < d.length; i++) d[i] = v.get(i).asDouble();
      out.add(d);
    }
    if (!missing.isEmpty()) {
      for (String t : missing) logMiss(t);
      throw new EmbedderException("Gemini embeddings 503", 503);
    }
    return out;
  }

  private void logMiss(String text) {
    if (missLog == null) return;
    String line =
        "{\"kind\":\"embed\",\"sha256\":\""
            + io.scos.engine.support.text.Text.sha256Hex(text)
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
