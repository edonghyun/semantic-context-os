package io.scos.provider.gemini;

import static io.scos.support.FakeHttpServer.ok;
import static io.scos.support.ProviderCallFakes.executor;
import static io.scos.support.ProviderCallFakes.startHttp;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderContract;
import io.scos.support.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.ObjectMapper;

/** Gemini 임베딩 어댑터가 임베더 계약을 지킨다 — 가짜 Gemini는 받은 요청 수만큼 기본 차원 벡터로 답한다. */
@DisplayName("Gemini 임베딩 어댑터는 임베더 계약을 지킨다")
class GeminiEmbedderContractTest extends EmbedderContract {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String ENDPOINT = "/v1beta/models/gemini-embedding-001:batchEmbedContents";

  final FakeHttpServer http = startHttp();

  @Override
  protected Embedder newEmbedder() {
    http.on(
        "POST",
        ENDPOINT,
        request ->
            ok(
                GeminiEmbedderTest.vectors(
                    JSON.readTree(request.body()).get("requests").size(),
                    GeminiEmbedder.DEFAULT_DIM)));
    return new GeminiEmbedder(
        http.baseUrl(),
        "test-key",
        GeminiEmbedder.DEFAULT_MODEL,
        GeminiEmbedder.DEFAULT_DIM,
        executor(),
        JSON);
  }

  @AfterEach
  void stop() {
    http.assertNoUnmatched();
    http.close();
  }
}
