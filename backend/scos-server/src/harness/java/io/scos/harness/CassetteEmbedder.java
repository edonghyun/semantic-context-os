package io.scos.harness;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 하네스 카세트 재생 임베더(ADR-0017, 제품 JAR 밖): 텍스트 sha256 → 벡터(카세트 `embed`). 미스는 미스 로그에 쓰고 {@link
 * Embedder.EmbedderException}("Gemini embeddings 503" — 기록한 shim이 503에 내던 메시지와 같다)을 던진다. 실 네트워크 없음.
 */
final class CassetteEmbedder implements Embedder {
  /** 카세트를 기록한 임베더(Gemini 기본 모델·차원). */
  private static final EmbedderIdentity RECORDED =
      new EmbedderIdentity("gemini", "gemini-embedding-001", 768);

  private final Cassette cassette;

  CassetteEmbedder(Path cassette, Path missLog, ObjectMapper json) {
    this.cassette = Cassette.section("embed", cassette, missLog, json);
  }

  @Override
  public EmbedderIdentity identity() {
    return RECORDED;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    List<double[]> out = new ArrayList<>(texts.size());
    List<String> missing = new ArrayList<>();
    for (String t : texts) {
      Optional<JsonNode> v = cassette.lookup(t);
      if (v.isEmpty()) {
        missing.add(t);
        continue;
      }
      out.add(vector(v.get()));
    }
    if (!missing.isEmpty()) {
      for (String t : missing) cassette.recordMiss(t);
      throw new EmbedderException("Gemini embeddings 503", 503);
    }
    return out;
  }

  private static double[] vector(JsonNode v) {
    double[] d = new double[v.size()];
    for (int i = 0; i < d.length; i++) d[i] = v.get(i).asDouble();
    return d;
  }
}
