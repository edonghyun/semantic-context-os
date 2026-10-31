package io.scos.provider.openai;

import static io.scos.provider.openai.OpenAiCompatibleFakes.API_PATH;
import static io.scos.support.FakeHttpServer.status;
import static io.scos.support.ProviderCallFakes.startHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 공급자 팩토리(공개 배포 단계 5 T3, Spring 없음): 설정 record가 필수값을 환경변수 이름으로 검사하고, 팩토리는 그 설정과 받은 정책으로
 * 임베더·생성기를 만든다. 로컬 fake HTTP, 실 API 호출 없음.
 */
@DisplayName("OpenAI 호환 공급자 팩토리는 필수값을 검사하고 받은 정책으로 임베더·생성기를 만든다")
class OpenAiCompatibleProvidersTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String MODEL = "nomic-embed-text";
  static final String CHAT_MODEL = "llama3.2";
  static final int DIM = 4;
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final int UNAVAILABLE = 503;
  static final int MAX_TOKENS = 10;

  /** 재시도하면 드러나는 1회 정책(지터 0). */
  static final ProviderCallPolicy SINGLE_ATTEMPT =
      new ProviderCallPolicy(TIMEOUT, 1, TIMEOUT, () -> 0.0);

  final FakeHttpServer http = startHttp();

  @AfterEach
  void stop() {
    http.assertNoUnmatched();
    http.close();
  }

  String baseUrl() {
    return http.baseUrl() + API_PATH;
  }

  @Nested
  @DisplayName("임베더 설정은")
  class EmbedderSettings {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BASE_URL", "MODEL", "DIM"})
    @DisplayName("필수값이 하나라도 없으면 그 환경변수 이름으로 거부한다")
    void requiresEachValue(String missing) {
      // given
      String baseUrl = missing.equals("BASE_URL") ? " " : baseUrl();
      String model = missing.equals("MODEL") ? null : MODEL;
      Integer dim = missing.equals("DIM") ? null : DIM;

      // when·then
      assertThatThrownBy(
              () ->
                  new OpenAiCompatibleEmbedderSettings(
                      baseUrl, model, dim, Optional.empty(), TIMEOUT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "SCOS_EMBEDDER_"
                  + missing
                  + " is required when SCOS_EMBEDDER_PROVIDER=openai-compatible");
    }

    @Test
    @DisplayName("빈 API 키는 키가 없는 것으로 본다")
    void blankApiKeyIsAbsent() {
      // when·then
      assertThat(
              new OpenAiCompatibleEmbedderSettings(baseUrl(), MODEL, DIM, Optional.of(" "), TIMEOUT)
                  .apiKey())
          .isEmpty();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"BASE_URL", "MODEL"})
  @DisplayName("생성기 설정은 필수값이 없으면 그 환경변수 이름으로 거부한다")
  void generatorRequiresEachValue(String missing) {
    // given
    String baseUrl = missing.equals("BASE_URL") ? null : baseUrl();
    String model = missing.equals("MODEL") ? "" : CHAT_MODEL;

    // when·then
    assertThatThrownBy(
            () -> new OpenAiCompatibleGeneratorSettings(baseUrl, model, Optional.empty(), TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "SCOS_RERANK_" + missing + " is required when SCOS_RERANK_PROVIDER=openai-compatible");
  }

  @Test
  @DisplayName("임베더 identity는 openai-compatible과 설정한 모델·차원이다")
  void embedderIdentityFromSettings() {
    // when
    EmbedderIdentity identity =
        OpenAiCompatibleProviders.embedder(
                new OpenAiCompatibleEmbedderSettings(
                    baseUrl(), MODEL, DIM, Optional.empty(), TIMEOUT),
                SINGLE_ATTEMPT,
                Clock.systemUTC(),
                TestMetrics.providerCalls(),
                JSON)
            .identity();

    // then
    assertThat(identity).isEqualTo(new EmbedderIdentity("openai-compatible", MODEL, DIM));
  }

  @Test
  @DisplayName("임베더는 base URL의 /embeddings를 받은 정책으로 부른다")
  void embedderUsesGivenPolicy() {
    // given: 1회 정책이면 503에 다시 시도하지 않는다
    http.on("POST", API_PATH + "/embeddings", req -> status(UNAVAILABLE, "{}"));

    // when
    Throwable failure =
        catchThrowable(
            () ->
                OpenAiCompatibleProviders.embedder(
                        new OpenAiCompatibleEmbedderSettings(
                            baseUrl(), MODEL, DIM, Optional.empty(), TIMEOUT),
                        SINGLE_ATTEMPT,
                        Clock.systemUTC(),
                        TestMetrics.providerCalls(),
                        JSON)
                    .embed(List.of("q")));

    // then: 503 한 번에 실패를 돌려준다
    assertThat(failure).isNotNull();
    assertThat(http.received())
        .extracting(Recorded::path)
        .containsExactly(API_PATH + "/embeddings");
  }

  @Test
  @DisplayName("생성기는 base URL의 /chat/completions를 받은 정책으로 부른다")
  void textGeneratorUsesGivenPolicy() {
    // given: 1회 정책이면 503에 다시 시도하지 않는다
    http.on("POST", API_PATH + "/chat/completions", req -> status(UNAVAILABLE, "{}"));

    // when
    Throwable failure =
        catchThrowable(
            () ->
                OpenAiCompatibleProviders.textGenerator(
                        new OpenAiCompatibleGeneratorSettings(
                            baseUrl(), CHAT_MODEL, Optional.empty(), TIMEOUT),
                        SINGLE_ATTEMPT,
                        Clock.systemUTC(),
                        TestMetrics.providerCalls(),
                        JSON)
                    .generate("p", MAX_TOKENS));

    // then: 503 한 번에 실패를 돌려준다
    assertThat(failure).isNotNull();
    assertThat(http.received())
        .extracting(Recorded::path)
        .containsExactly(API_PATH + "/chat/completions");
  }
}
