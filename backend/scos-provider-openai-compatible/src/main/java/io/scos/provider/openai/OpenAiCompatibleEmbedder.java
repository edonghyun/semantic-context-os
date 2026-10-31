package io.scos.provider.openai;

import io.scos.engine.support.call.EmbeddingReply;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 /embeddings 어댑터(공개 배포 단계 5 T2): {@code POST <base URL>/embeddings}에 {@code
 * {"model","input"}}를 100개씩 순차로 보낸다. {@code dimensions}는 보내지 않는다 — 모르거나 거부하는 서버가 있어, 대신 응답 길이를 설정
 * 차원과 비교한다. 서버가 data를 index 순서가 아니게 줄 수 있어 index 자리에 놓는다. 실패 메시지는 "OpenAI-compatible embeddings
 * <status>[: <error 코드>]"이고 본문은 싣지 않는다(H6).
 */
public final class OpenAiCompatibleEmbedder implements Embedder {
  static final String PROVIDER = "openai-compatible";
  static final int MAX_BATCH = 100;
  private final ObjectMapper json;
  private final ProviderJsonHttp http;
  private final URI endpoint;
  private final EmbedderIdentity identity;
  private final EmbeddingReply reply;

  public OpenAiCompatibleEmbedder(
      String baseUrl,
      Optional<String> apiKey,
      String model,
      int dim,
      ProviderCallExecutor executor,
      ObjectMapper json) {
    this.json = json;
    this.http = OpenAiCompatibleHttp.over(executor, apiKey);
    this.identity = new EmbedderIdentity(PROVIDER, model, dim);
    this.reply = new EmbeddingReply("OpenAI-compatible", dim);
    this.endpoint = OpenAiCompatibleHttp.endpoint(baseUrl, "/embeddings");
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
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", identity.model());
    request.put("input", texts);
    String text;
    try {
      text = http.post("openai.embed", endpoint, json.writeValueAsString(request));
    } catch (ProviderJsonHttp.Failure f) {
      throw reply.failure(f);
    }
    JsonNode data = reply.parse(json, text).path("data");
    if (!data.isArray()) throw reply.malformed("no data");
    reply.requireCount(data.size(), texts.size());
    // 벡터는 index 자리에 놓는다 — 범위 밖·중복 index는 형식 위반이다
    double[][] out = new double[texts.size()][];
    for (JsonNode item : data) {
      int index = item.path("index").asInt(-1);
      if (index < 0 || index >= out.length || out[index] != null) throw reply.malformed("index");
      out[index] = reply.vector(item.path("embedding"));
    }
    return Arrays.asList(out);
  }
}
