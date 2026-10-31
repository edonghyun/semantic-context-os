package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicies;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.provider.gemini.GeminiEmbedder;
import io.scos.provider.gemini.GeminiTextGenerator;
import io.scos.provider.openai.OpenAiCompatibleEmbedder;
import io.scos.provider.openai.OpenAiCompatibleTextGenerator;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.TextGenerator;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * 공급자 선택 조립(공개 배포 단계 5 T3, 슬라이스·DB 없음): 임베더·재정렬 공급자는 설정으로 고르고, Gemini 키는 Gemini를 쓰는 역할이 있을 때만 요구하며,
 * 사용자 구성이 먼저 등록한 공급자 빈이 이긴다. 필수값·정책 값·identity의 세부는 공급자 팩토리 테스트의 몫이다.
 */
@DisplayName("공급자는 설정으로 고르고, 쓰는 공급자의 설정만 요구한다")
class ProvidersConfigurationTest {
  static final String OPENAI_BASE_URL = "http://127.0.0.1:1/v1";
  static final String OPENAI_MODEL = "nomic-embed-text";
  static final int OPENAI_DIM = 4;
  static final String CHAT_MODEL = "llama3.2";
  static final Duration OPENAI_TIMEOUT = Duration.ofSeconds(7);
  static final String GEMINI_MODEL = "gemini-embedding-002";
  static final int GEMINI_DIM = 1536;

  static final String[] OPENAI_EMBEDDER = {
    "scos.embedder.provider=openai-compatible",
    "scos.embedder.base-url=" + OPENAI_BASE_URL,
    "scos.embedder.model=" + OPENAI_MODEL,
    "scos.embedder.dim=" + OPENAI_DIM
  };

  /** 공급자 조립이 요구하는 시계·지표·매퍼만 있는 바탕(ProvidersConfiguration은 아직 없다). */
  final ApplicationContextRunner base =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(ProviderCallMetrics.class, TestMetrics::providerCalls);

  final ApplicationContextRunner providers =
      base.withUserConfiguration(ProvidersConfiguration.class);

  /** 기동 실패의 근본 원인이 그 프로퍼티의 Bean Validation인지 — 무관한 실패를 성공으로 세지 않는다. */
  static void assertBindValidationFailure(AssertableApplicationContext ctx, String property) {
    assertThat(ctx)
        .getFailure()
        .rootCause()
        .isInstanceOf(BindValidationException.class)
        .hasMessageContaining(property);
  }

  @Nested
  @DisplayName("임베더 공급자를 고르면")
  class EmbedderChoice {
    @Test
    @DisplayName("gemini면 identity가 gemini·gemini-embedding-001·768인 임베더를 조립한다")
    void geminiEmbedder() {
      // when·then
      providers
          .withPropertyValues(
              "scos.embedder.provider=gemini", "scos.gemini.api-key=k", "scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx.getBean(Embedder.class))
                      .isInstanceOf(GeminiEmbedder.class)
                      .extracting(Embedder::identity)
                      .isEqualTo(
                          new EmbedderIdentity(
                              "gemini", GeminiEmbedder.DEFAULT_MODEL, GeminiEmbedder.DEFAULT_DIM)));
    }

    @Test
    @DisplayName("openai-compatible이면 설정한 모델·차원의 OpenAI 호환 임베더를 조립한다")
    void openAiCompatibleEmbedder() {
      // when·then
      providers
          .withPropertyValues(OPENAI_EMBEDDER)
          .withPropertyValues("scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx.getBean(Embedder.class))
                      .isInstanceOf(OpenAiCompatibleEmbedder.class)
                      .extracting(Embedder::identity)
                      .isEqualTo(
                          new EmbedderIdentity("openai-compatible", OPENAI_MODEL, OPENAI_DIM)));
    }

    @Test
    @DisplayName("openai-compatible의 필수값이 없으면 그 환경변수 이름으로 기동에 실패한다")
    void openAiCompatibleMissingValueFailsStartup() {
      // when·then
      providers
          .withPropertyValues(
              "scos.embedder.provider=openai-compatible",
              "scos.embedder.base-url=" + OPENAI_BASE_URL,
              "scos.embedder.model=" + OPENAI_MODEL,
              "scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(IllegalArgumentException.class)
                      .hasMessageContaining("SCOS_EMBEDDER_DIM"));
    }

    @Test
    @DisplayName("목록 밖 공급자면 기동에 실패한다")
    void rejectsUnknownProvider() {
      // when·then
      providers
          .withPropertyValues(
              "scos.embedder.provider=bogus", "scos.gemini.api-key=k", "scos.rerank.provider=none")
          .run(ctx -> assertBindValidationFailure(ctx, "provider"));
    }

    @Test
    @DisplayName("공급자를 지정하지 않으면 기본 공급자의 임베더를 조립한다")
    void defaultProviderMatchesDefaultBean() {
      // when·then: 바인딩된 기본값과 조건(ProviderSelected)의 기본값이 같은 공급자를 가리킨다
      providers
          .withPropertyValues("scos.gemini.api-key=k", "scos.rerank.provider=none")
          .run(
              ctx -> {
                assertThat(ctx.getBean(EmbedderProperties.class).provider()).isEqualTo("gemini");
                assertThat(ctx.getBean(Embedder.class)).isInstanceOf(GeminiEmbedder.class);
              });
    }

    @Test
    @DisplayName("gemini여도 모델·차원을 주면 그 값이다")
    void geminiModelAndDimFromSettings() {
      // when·then
      providers
          .withPropertyValues(
              "scos.embedder.provider=gemini",
              "scos.embedder.model=" + GEMINI_MODEL,
              "scos.embedder.dim=" + GEMINI_DIM,
              "scos.gemini.api-key=k",
              "scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx.getBean(Embedder.class).identity())
                      .isEqualTo(new EmbedderIdentity("gemini", GEMINI_MODEL, GEMINI_DIM)));
    }
  }

  /**
   * 빈 환경변수(예: {@code SCOS_EMBEDDER_PROVIDER=})는 지정하지 않은 것과 같다. 테스트 속성 값은 앞뒤 공백을 잘라 내므로 원문 그대로 넣는다.
   */
  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "scos.embedder.provider, ''",
    "scos.embedder.provider, ' '",
    "scos.rerank.provider, ''",
    "scos.rerank.provider, ' '"
  })
  @DisplayName("공급자를 빈 값으로 주면 기본 공급자로 조립한다")
  void blankProviderIsDefault(String property, String blank) {
    // when·then: 다른 역할은 지정하지 않아 기본 공급자다
    providers
        .withPropertyValues("scos.gemini.api-key=k")
        .withInitializer(
            ctx ->
                ctx.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("blank", Map.of(property, blank))))
        .run(
            ctx -> {
              assertThat(ctx.getBean(Embedder.class)).isInstanceOf(GeminiEmbedder.class);
              assertThat(ctx.getBean(TextGenerator.class)).isInstanceOf(GeminiTextGenerator.class);
            });
  }

  @Nested
  @DisplayName("GEMINI_API_KEY는")
  class GeminiApiKey {
    @Test
    @DisplayName("두 역할 모두 gemini가 아니면 없어도 기동한다")
    void notRequiredWithoutGemini() {
      // when·then
      providers
          .withPropertyValues(OPENAI_EMBEDDER)
          .withPropertyValues("scos.rerank.provider=none")
          .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(Embedder.class));
    }

    @Test
    @DisplayName("임베더만 gemini여도 없으면 apiKey 검증 오류로 실패한다")
    void requiredForGeminiEmbedder() {
      // when·then
      providers
          .withPropertyValues("scos.embedder.provider=gemini", "scos.rerank.provider=none")
          .run(ctx -> assertBindValidationFailure(ctx, "apiKey"));
    }

    @Test
    @DisplayName("재정렬만 gemini여도 없으면 apiKey 검증 오류로 실패한다")
    void requiredForGeminiRerank() {
      // when·then
      providers
          .withPropertyValues(OPENAI_EMBEDDER)
          .withPropertyValues("scos.rerank.provider=gemini")
          .run(ctx -> assertBindValidationFailure(ctx, "apiKey"));
    }

    @Test
    @DisplayName("키가 있으면 Gemini 임베더와 생성기를 조립한다")
    void geminiBeansWithKey() {
      // when·then
      providers
          .withPropertyValues("scos.gemini.api-key=k")
          .run(
              ctx -> {
                assertThat(ctx.getBean(Embedder.class)).isInstanceOf(GeminiEmbedder.class);
                assertThat(ctx.getBean(TextGenerator.class))
                    .isInstanceOf(GeminiTextGenerator.class);
              });
    }
  }

  @Nested
  @DisplayName("재정렬 공급자를 고르면")
  class RerankChoice {
    @Test
    @DisplayName("none이면 생성기를 조립하지 않는다")
    void noneAssemblesNoGenerator() {
      // when·then
      providers
          .withPropertyValues("scos.gemini.api-key=k", "scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx)
                      .hasSingleBean(Embedder.class)
                      .doesNotHaveBean(TextGenerator.class));
    }

    @Test
    @DisplayName("openai-compatible이면 OpenAI 호환 생성기를 조립한다")
    void openAiCompatibleGenerator() {
      // when·then
      providers
          .withPropertyValues(OPENAI_EMBEDDER)
          .withPropertyValues(
              "scos.rerank.provider=openai-compatible",
              "scos.rerank.base-url=" + OPENAI_BASE_URL,
              "scos.rerank.model=" + CHAT_MODEL)
          .run(
              ctx ->
                  assertThat(ctx.getBean(TextGenerator.class))
                      .isInstanceOf(OpenAiCompatibleTextGenerator.class));
    }

    @Test
    @DisplayName("gemini면 모델 기본값은 gemini-2.5-flash다")
    void geminiDefaultModel() {
      // when·then
      providers
          .withPropertyValues("scos.gemini.api-key=k", "scos.rerank.provider=gemini")
          .run(
              ctx ->
                  assertThat(ctx.getBean(RerankProperties.class).model())
                      .isEqualTo("gemini-2.5-flash"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class UserProviders {
    static final Embedder EMBEDDER =
        new Embedder() {
          @Override
          public EmbedderIdentity identity() {
            return new EmbedderIdentity("user", "user-model", OPENAI_DIM);
          }

          @Override
          public List<double[]> embed(List<String> texts) {
            return List.of();
          }
        };
    static final TextGenerator GENERATOR = (prompt, maxTokens) -> "";

    @Bean
    Embedder userEmbedder() {
      return EMBEDDER;
    }

    @Bean
    TextGenerator userGenerator() {
      return GENERATOR;
    }
  }

  @Nested
  @DisplayName("사용자 구성이 공급자 빈을 먼저 등록하면")
  class UserBeansFirst {
    final ApplicationContextRunner userFirst =
        base.withUserConfiguration(UserProviders.class, ProvidersConfiguration.class)
            .withPropertyValues("scos.gemini.api-key=k");

    @Test
    @DisplayName("사용자 Embedder가 이기고 기본 임베더는 조립하지 않는다")
    void userEmbedderWins() {
      // when·then
      userFirst.run(
          ctx ->
              assertThat(ctx.getBeansOfType(Embedder.class))
                  .containsOnlyKeys("userEmbedder")
                  .containsValue(UserProviders.EMBEDDER));
    }

    @Test
    @DisplayName("사용자 TextGenerator가 이긴다")
    void userGeneratorWins() {
      // when·then
      userFirst.run(
          ctx ->
              assertThat(ctx.getBeansOfType(TextGenerator.class))
                  .containsOnlyKeys("userGenerator")
                  .containsValue(UserProviders.GENERATOR));
    }
  }

  @Nested
  @DisplayName("호출 정책")
  class CallPolicy {
    @Test
    @DisplayName("OpenAI 호환 임베더의 mcp 정책은 1회 시도·총 예산 = 시도 timeout이다")
    void openAiCompatibleMcpPolicy() {
      // when·then
      providers
          .withPropertyValues(OPENAI_EMBEDDER)
          .withPropertyValues(
              "spring.profiles.active=mcp",
              "scos.rerank.provider=none",
              "scos.embedder.timeout=" + OPENAI_TIMEOUT.toSeconds() + "s")
          .run(
              ctx ->
                  assertThat(ctx.getBean(ProviderCallPolicies.class).embedding())
                      .extracting(
                          ProviderCallPolicy::requestTimeout,
                          ProviderCallPolicy::maxAttempts,
                          ProviderCallPolicy::maxElapsed)
                      .containsExactly(OPENAI_TIMEOUT, 1, OPENAI_TIMEOUT));
    }
  }
}
