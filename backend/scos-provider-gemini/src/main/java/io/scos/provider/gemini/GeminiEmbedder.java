package io.scos.provider.gemini;

import io.scos.engine.support.call.EmbeddingReply;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini batchEmbedContents 어댑터(기본 gemini-embedding-001, 768차원, 100개 배치 순차). 모델·차원은 생성자로 받고
 * identity는 ("gemini", 모델, 차원)이다(stage2 §2.2). 실패 메시지는 {@link EmbeddingReply}("Gemini embeddings
 * <status>[: <error 코드>]"). base URL은 설정으로 바꿀 수 있다 — 하네스 재생 서버로 돌릴 때(ADR-0017). 실 호출은 테스트에서 하지 않는다.
 */
public final class GeminiEmbedder implements Embedder {
  public static final String DEFAULT_MODEL = "gemini-embedding-001";
  public static final int DEFAULT_DIM = 768;
  static final int MAX_BATCH = 100;
  private final ObjectMapper json;
  private final ProviderJsonHttp http;
  private final URI endpoint;
  private final EmbedderIdentity identity;
  private final EmbeddingReply reply;

  public GeminiEmbedder(
      String baseUrl,
      String apiKey,
      String model,
      int dim,
      ProviderCallExecutor executor,
      ObjectMapper json) {
    this.json = json;
    this.http = GeminiHttp.over(executor, apiKey);
    this.identity = new EmbedderIdentity("gemini", model, dim);
    this.reply = new EmbeddingReply("Gemini", dim);
    this.endpoint =
        URI.create(
            baseUrl.replaceAll("/$", "") + "/v1beta/models/" + model + ":batchEmbedContents");
  }

  @Override
  public EmbedderIdentity identity() {
    return identity;
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
      r.put("model", "models/" + identity.model());
      r.put("content", Map.of("parts", List.of(Map.of("text", t))));
      r.put("outputDimensionality", identity.dim());
      requests.add(r);
    }
    String text;
    try {
      text =
          http.post(
              "gemini.embed", endpoint, json.writeValueAsString(Map.of("requests", requests)));
    } catch (ProviderJsonHttp.Failure f) {
      throw reply.failure(f);
    }
    JsonNode embeddings = reply.parse(json, text).path("embeddings");
    if (!embeddings.isArray()) throw reply.malformed("no embeddings");
    reply.requireCount(embeddings.size(), texts.size());
    List<double[]> out = new ArrayList<>();
    for (JsonNode e : embeddings) out.add(reply.vector(e.path("values")));
    return out;
  }
}
