package io.scos.provider.gemini;

import static io.scos.support.FakeHttpServer.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.ProviderCallFakes;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini 공급자 팩토리(공개 배포 단계 5 T3, Spring 없음): 설정 record → 임베더·생성기. identity는 설정의 모델·차원이고, 호출은 받은 정책으로
 * 한다. 로컬 fake HTTP, 실 API 호출 없음.
 */
@DisplayName("Gemini 공급자 팩토리는 설정과 받은 정책으로 임베더·생성기를 만든다")
class GeminiProvidersTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String API_KEY = "k";
  static final String MODEL = "gemini-embedding-002";
  static final int DIM = 1536;
  static final String RERANK_MODEL = "gemini-2.5-flash-lite";
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final int UNAVAILABLE = 503;
  static final int MAX_TOKENS = 10;

  /** 재시도하면 드러나는 1회 정책(지터 0). */
  static final ProviderCallPolicy SINGLE_ATTEMPT =
      new ProviderCallPolicy(TIMEOUT, 1, TIMEOUT, () -> 0.0);

  final FakeHttpServer http = ProviderCallFakes.startHttp();

  @AfterEach
  void stop() {
    http.assertNoUnmatched();
    http.close();
  }

  GeminiSettings settings(String model, Integer dim) {
    return new GeminiSettings(API_KEY, http.baseUrl(), model, dim, TIMEOUT);
  }

  @Test
  @DisplayName("임베더 identity는 gemini와 설정한 모델·차원이다")
  void embedderIdentityFromSettings() {
    // when
    EmbedderIdentity identity =
        GeminiProviders.embedder(
                settings(MODEL, DIM),
                SINGLE_ATTEMPT,
                Clock.systemUTC(),
                TestMetrics.providerCalls(),
                JSON)
            .identity();

    // then
    assertThat(identity).isEqualTo(new EmbedderIdentity("gemini", MODEL, DIM));
  }

  @Test
  @DisplayName("모델·차원을 주지 않으면 gemini-embedding-001·768이다")
  void defaultModelAndDim() {
    // when·then
    assertThat(settings(null, null))
        .extracting(GeminiSettings::model, GeminiSettings::dim)
        .containsExactly(GeminiEmbedder.DEFAULT_MODEL, GeminiEmbedder.DEFAULT_DIM);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", " "})
  @DisplayName("API 키가 비면 GEMINI_API_KEY 이름으로 거부한다")
  void requiresApiKey(String apiKey) {
    // when·then
    assertThatThrownBy(() -> new GeminiSettings(apiKey, http.baseUrl(), MODEL, DIM, TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GEMINI_API_KEY");
  }

  @Test
  @DisplayName("임베더는 설정한 모델 경로를 받은 정책으로 부른다")
  void embedderUsesGivenPolicy() {
    // given: 1회 정책이면 503에 다시 시도하지 않는다
    String endpoint = "/v1beta/models/" + MODEL + ":batchEmbedContents";
    http.on("POST", endpoint, req -> status(UNAVAILABLE, "{}"));

    // when
    Throwable failure =
        catchThrowable(
            () ->
                GeminiProviders.embedder(
                        settings(MODEL, DIM),
                        SINGLE_ATTEMPT,
                        Clock.systemUTC(),
                        TestMetrics.providerCalls(),
                        JSON)
                    .embed(List.of("q")));

    // then: 503 한 번에 실패를 돌려준다
    assertThat(failure).isNotNull();
    assertThat(http.received()).extracting(Recorded::path).containsExactly(endpoint);
  }

  @Test
  @DisplayName("생성기는 받은 모델 경로를 받은 정책으로 부른다")
  void textGeneratorUsesGivenModelAndPolicy() {
    // given: 1회 정책이면 503에 다시 시도하지 않는다
    String endpoint = "/v1beta/models/" + RERANK_MODEL + ":generateContent";
    http.on("POST", endpoint, req -> status(UNAVAILABLE, "{}"));

    // when
    Throwable failure =
        catchThrowable(
            () ->
                GeminiProviders.textGenerator(
                        settings(null, null),
                        RERANK_MODEL,
                        SINGLE_ATTEMPT,
                        Clock.systemUTC(),
                        TestMetrics.providerCalls(),
                        JSON)
                    .generate("p", MAX_TOKENS));

    // then: 503 한 번에 실패를 돌려준다
    assertThat(failure).isNotNull();
    assertThat(http.received()).extracting(Recorded::path).containsExactly(endpoint);
  }
}
