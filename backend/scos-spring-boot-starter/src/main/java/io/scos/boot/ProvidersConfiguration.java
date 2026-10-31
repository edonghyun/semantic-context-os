package io.scos.boot;

import io.scos.boot.ProviderSelected.Role;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicies;
import io.scos.provider.gemini.GeminiProviders;
import io.scos.provider.gemini.GeminiSettings;
import io.scos.provider.openai.OpenAiCompatibleEmbedderSettings;
import io.scos.provider.openai.OpenAiCompatibleGeneratorSettings;
import io.scos.provider.openai.OpenAiCompatibleProviders;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * 공급자 조립(공개 배포 단계 5 T3): 임베더(scos.embedder.provider)와 재정렬 생성기(scos.rerank.provider)를 설정으로 고른다. 조립
 * 자체는 공급자 모듈의 Spring 없는 팩토리가 하고, 여기 빈은 설정 → 설정 record 변환과 팩토리 호출만 한다. 실행기는 {@link
 * ProviderCallPolicies} 빈의 값으로만 만든다. 사용자 구성이 먼저 등록한 Embedder·TextGenerator가 있으면 조립하지 않는다(자동 구성이 아니라
 * 등록 순서에 기댄다 — 0.1.0에서 공개 약속이 아니다). `harness` 프로필의 카세트 어댑터는 제품 JAR 밖(src/harness)에 있어 공급자 조립은 그
 * 프로필에서 비활성이다(ADR-0017, 리뷰 I-6). 재정렬 설정은 프로필 무관(리뷰 M-7: 소유자 하나).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
public class ProvidersConfiguration {
  static final String GEMINI = "gemini";
  static final String OPENAI_COMPATIBLE = "openai-compatible";

  /** 호출 정책: 프로세스 용도(검색 대 수집·투영)에 따라 하나가 조립된다. 시도 timeout은 역할마다 그 공급자 설정에서 온다. */
  @Configuration(proxyBeanMethods = false)
  @Profile("!harness")
  @EnableConfigurationProperties(EmbedderProperties.class)
  static class CallPolicies {
    /** 검색(MCP 프로세스: 질의 임베딩·재정렬)은 1회 시도 — 재정렬은 폴백, 질의 임베딩은 오류(B11). */
    @Bean
    @Profile("mcp")
    ProviderCallPolicies interactiveProviderCalls(
        EmbedderProperties embedder,
        RerankProperties rerank,
        ObjectProvider<GeminiProperties> gemini) {
      return policies(true, embedder, rerank, gemini);
    }

    /** 수집·투영 명령(데몬 포함)은 재시도. */
    @Bean
    @Profile("!mcp")
    ProviderCallPolicies batchProviderCalls(
        EmbedderProperties embedder,
        RerankProperties rerank,
        ObjectProvider<GeminiProperties> gemini) {
      return policies(false, embedder, rerank, gemini);
    }

    /** gemini 역할의 timeout은 scos.gemini.timeout(그 역할이 있으면 GeminiProperties가 바인딩돼 있다). */
    private static ProviderCallPolicies policies(
        boolean interactive,
        EmbedderProperties embedder,
        RerankProperties rerank,
        ObjectProvider<GeminiProperties> gemini) {
      Duration embedding =
          embedder.provider().equals(GEMINI) ? gemini.getObject().timeout() : embedder.timeout();
      Optional<Duration> reranking =
          switch (rerank.provider()) {
            case "none" -> Optional.empty();
            case GEMINI -> Optional.of(gemini.getObject().timeout());
            default -> Optional.of(rerank.timeout());
          };
      return ProviderCallPolicies.of(interactive, embedding, reranking);
    }
  }

  /** 임베더나 재정렬 중 하나가 gemini다(빈 값은 각 설정의 기본 공급자, ProviderSelected). */
  static class GeminiInUse extends AnyNestedCondition {
    GeminiInUse() {
      super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnProvider(role = Role.EMBEDDER, value = GEMINI)
    static class Embedding {}

    @ConditionalOnProvider(role = Role.RERANK, value = GEMINI)
    static class Rerank {}
  }

  /** 임베더나 재정렬 중 하나가 openai-compatible이다. */
  static class OpenAiCompatibleInUse extends AnyNestedCondition {
    OpenAiCompatibleInUse() {
      super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnProvider(role = Role.EMBEDDER, value = OPENAI_COMPATIBLE)
    static class Embedding {}

    @ConditionalOnProvider(role = Role.RERANK, value = OPENAI_COMPATIBLE)
    static class Rerank {}
  }

  /** Gemini: 키는 Gemini를 쓰는 역할이 있을 때만 Bean Validation이 기동 시 거부한다(ADR-0009, 리뷰 I-7). */
  @Configuration(proxyBeanMethods = false)
  @Profile("!harness")
  @Conditional(GeminiInUse.class)
  @EnableConfigurationProperties(GeminiProperties.class)
  static class Gemini {
    @Bean
    @ConditionalOnProvider(role = Role.EMBEDDER, value = GEMINI)
    @ConditionalOnMissingBean(Embedder.class)
    Embedder geminiEmbedder(
        GeminiProperties gemini,
        EmbedderProperties embedder,
        ProviderCallPolicies policies,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return GeminiProviders.embedder(
          gemini.settings(embedder.model(), embedder.dim()),
          policies.embedding(),
          clock,
          metrics,
          json);
    }

    /** gemini면 생성기 빈, none이면 빈 없음(검색 조립이 비활성 정책으로 받는다). */
    @Bean
    @ConditionalOnProvider(role = Role.RERANK, value = GEMINI)
    @ConditionalOnMissingBean(TextGenerator.class)
    TextGenerator geminiTextGenerator(
        GeminiProperties gemini,
        RerankProperties rerank,
        ProviderCallPolicies policies,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return GeminiProviders.textGenerator(
          gemini.settings(null, null),
          rerank.model(),
          policies.rerank().orElseThrow(),
          clock,
          metrics,
          json);
    }
  }

  /** OpenAI 호환: 필수값은 설정 record가 환경변수 이름으로 검사한다(빠지면 기동 실패). */
  @Configuration(proxyBeanMethods = false)
  @Profile("!harness")
  @Conditional(OpenAiCompatibleInUse.class)
  static class OpenAiCompatible {
    @Bean
    @ConditionalOnProvider(role = Role.EMBEDDER, value = OPENAI_COMPATIBLE)
    @ConditionalOnMissingBean(Embedder.class)
    Embedder openAiCompatibleEmbedder(
        EmbedderProperties embedder,
        ProviderCallPolicies policies,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return OpenAiCompatibleProviders.embedder(
          new OpenAiCompatibleEmbedderSettings(
              embedder.baseUrl(),
              embedder.model(),
              embedder.dim(),
              Optional.ofNullable(embedder.apiKey()),
              embedder.timeout()),
          policies.embedding(),
          clock,
          metrics,
          json);
    }

    @Bean
    @ConditionalOnProvider(role = Role.RERANK, value = OPENAI_COMPATIBLE)
    @ConditionalOnMissingBean(TextGenerator.class)
    TextGenerator openAiCompatibleTextGenerator(
        RerankProperties rerank,
        ProviderCallPolicies policies,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return OpenAiCompatibleProviders.textGenerator(
          new OpenAiCompatibleGeneratorSettings(
              rerank.baseUrl(),
              rerank.model(),
              Optional.ofNullable(rerank.apiKey()),
              rerank.timeout()),
          policies.rerank().orElseThrow(),
          clock,
          metrics,
          json);
    }
  }

  @Validated
  @ConfigurationProperties("scos.gemini")
  public record GeminiProperties(@NotBlank String apiKey, String baseUrl, Duration timeout) {
    public GeminiProperties {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? GeminiSettings.DEFAULT_BASE_URL : baseUrl;
      timeout = timeout == null ? GeminiSettings.DEFAULT_TIMEOUT : timeout;
    }

    /** 이 접속 설정과 임베딩 모델·차원(null이면 Gemini 기본값)의 팩토리 설정. */
    GeminiSettings settings(@Nullable String model, @Nullable Integer dim) {
      return new GeminiSettings(apiKey, baseUrl, model, dim, timeout);
    }

    /** API 키는 싣지 않고 있는지만 적는다 — 로그·바인딩 실패 메시지·예외로 새지 않게. */
    @Override
    public String toString() {
      return "GeminiProperties[apiKey="
          + (apiKey == null ? "<none>" : "***")
          + ", baseUrl="
          + baseUrl
          + ", timeout="
          + timeout
          + "]";
    }
  }
}
