package io.scos.provider.gemini;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.spi.embedding.Embedder;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini batchEmbedContents 어댑터(TS GeminiEmbedder와 같은 요청: gemini-embedding-001, 768차원, 100개 배치 순차).
 * 실패 메시지는 TS summarizeEmbedderFailure 형식("Gemini embeddings <status>[: <error 코드>]"). base URL은
 * 설정으로 바꿀 수 있다 — 하네스 재생 서버로 돌릴 때(ADR-0017). 실 호출은 테스트에서 하지 않는다.
 */
public final class GeminiEmbedder implements Embedder {
  static final String MODEL = "gemini-embedding-001";
  static final int DIM = 768;
  static final int MAX_BATCH = 100;
  private final ObjectMapper json;
  private final GeminiHttp http;
  private final URI endpoint;

  public GeminiEmbedder(
      String baseUrl, String apiKey, ProviderCallExecutor executor, ObjectMapper json) {
    this.json = json;
    this.http = new GeminiHttp(executor, apiKey);
    this.endpoint =
        URI.create(
            baseUrl.replaceAll("/$", "") + "/v1beta/models/" + MODEL + ":batchEmbedContents");
  }

  @Override
  public int dim() {
    return DIM;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    List<double[]> out = new ArrayList<>(texts.size());
    for (int offset = 0; offset < texts.size(); offset += MAX_BATCH)
      out.addAll(embedBatch(texts.subList(offset, Math.min(texts.size(), offset + MAX_BATCH))));
    return out;
  }

  private List<double[]> embedBatch(List<String> texts) {
    List<Map<String, Object>> requests = new ArrayList<>();
    for (String t : texts) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("model", "models/" + MODEL);
      r.put("content", Map.of("parts", List.of(Map.of("text", t))));
      r.put("outputDimensionality", DIM);
      requests.add(r);
    }
    String text;
    try {
      text =
          http.post(
              "gemini.embed", endpoint, json.writeValueAsString(Map.of("requests", requests)));
    } catch (GeminiHttp.GeminiFailure f) {
      // 상태가 있으면 그대로(투영이 429·5xx를 reason으로 분류), 전송 오류는 종류 이름만 — 본문은 싣지 않는다(H6)
      throw f.status()
          .map(
              s ->
                  new EmbedderException(
                      "Gemini embeddings " + s + f.errorCode().map(c -> ": " + c).orElse(""), s))
          .orElseGet(
              () ->
                  new EmbedderException(
                      "Gemini embeddings request failed: " + f.failure().kind().wire()));
    }
    JsonNode body;
    try {
      body = json.readTree(text);
    } catch (RuntimeException e) {
      throw new EmbedderException("Gemini embeddings malformed response");
    }
    JsonNode embeddings = body.path("embeddings");
    if (!embeddings.isArray())
      throw new EmbedderException("Gemini embeddings malformed response: no embeddings");
    // 공급자가 일부만 돌려주면 texts[i] → vectors[i] 정렬이 조용히 어긋난다(리뷰 I-9) — 개수·차원을 검증한다
    if (embeddings.size() != texts.size())
      throw new EmbedderException(
          "Gemini embeddings count mismatch: " + embeddings.size() + " != " + texts.size());
    List<double[]> out = new ArrayList<>();
    for (JsonNode e : embeddings) {
      JsonNode values = e.path("values");
      if (values.size() != DIM)
        throw new EmbedderException(
            "Gemini embeddings dim mismatch: " + values.size() + " != " + DIM);
      double[] v = new double[values.size()];
      for (int i = 0; i < v.length; i++) v[i] = values.get(i).asDouble();
      out.add(v);
    }
    return out;
  }
}
