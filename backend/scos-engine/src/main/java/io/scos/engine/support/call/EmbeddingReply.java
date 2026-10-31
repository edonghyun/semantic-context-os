package io.scos.engine.support.call;

import io.scos.spi.embedding.Embedder.EmbedderException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 임베딩 공급자 응답의 공통 검사와 실패 문구. 문구는 "{이름표} embeddings …"다: 상태가 있으면 "{이름표} embeddings {상태}[: {error
 * 코드}]"(상태를 실어 투영이 429·5xx를 분류한다), 전송 오류·취소는 "… request failed: {종류}" — 본문은 싣지 않는다(H6). 공급자가 일부만
 * 돌려주면 texts[i] → vectors[i] 정렬이 조용히 어긋나므로 개수와 차원을 확인한다. 벡터가 응답의 어디에 있는지는 공급자가 안다.
 */
public final class EmbeddingReply {
  private final String label;
  private final int dim;

  /**
   * @param provider 실패 문구의 공급자 이름표(예: "Gemini")
   */
  public EmbeddingReply(String provider, int dim) {
    this.label = provider + " embeddings";
    this.dim = dim;
  }

  public EmbedderException failure(ProviderJsonHttp.Failure f) {
    return f.status()
        .map(
            s ->
                new EmbedderException(
                    label + " " + s + f.errorCode().map(c -> ": " + c).orElse(""), s))
        .orElseGet(
            () -> new EmbedderException(label + " request failed: " + f.failure().kind().wire()));
  }

  public JsonNode parse(ObjectMapper json, String text) {
    try {
      return json.readTree(text);
    } catch (RuntimeException e) {
      throw new EmbedderException(label + " malformed response");
    }
  }

  /** "{이름표} embeddings malformed response: {무엇}". */
  public EmbedderException malformed(String what) {
    return new EmbedderException(label + " malformed response: " + what);
  }

  public void requireCount(int returned, int requested) {
    if (returned != requested)
      throw new EmbedderException(label + " count mismatch: " + returned + " != " + requested);
  }

  /** 길이가 dim인 수 배열을 벡터로. */
  public double[] vector(JsonNode values) {
    if (values.size() != dim)
      throw new EmbedderException(label + " dim mismatch: " + values.size() + " != " + dim);
    double[] v = new double[values.size()];
    for (int i = 0; i < v.length; i++) v[i] = values.get(i).asDouble();
    return v;
  }
}
