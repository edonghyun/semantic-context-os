package io.scos.boot;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.provider.gemini.GeminiEmbedder;
import io.scos.provider.gemini.GeminiTextGenerator;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * 임베더 조립(기본 프로필): Gemini — 키는 Bean Validation이 기동 시 거부한다(ADR-0009, 리뷰 I-7). `harness` 프로필의 카세트 임베더는
 * 제품 JAR 밖 (src/harness, scos-harness-adapters.jar)에 있어 이 구성은 그 프로필에서 비활성이다(ADR-0017, 리뷰 I-6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
public class ProvidersConfiguration {
  /**
   * Gemini(기본 프로필): 키는 Bean Validation이 기동 시 거부(ADR-0009, 리뷰 I-7). harness 프로필의 카세트 어댑터는 제품 JAR
   * 밖(ADR-0017). 재정렬 설정은 바깥(프로필 무관, 리뷰 M-7: 소유자 하나).
   */
  @Configuration(proxyBeanMethods = false)
  @Profile("!harness")
  @EnableConfigurationProperties(ProvidersConfiguration.GeminiProperties.class)
  static class Gemini {
    /** 정책 위의 실행기. 빈으로 두지 않는다(수집 런타임의 ProviderCallExecutor 빈과 타입이 겹쳐 주입이 모호해진다). */
    private static ProviderCallExecutor executor(
        GeminiCallPolicy calls, Clock clock, ProviderCallMetrics metrics) {
      return new ProviderCallExecutor(
          calls.policy(), clock, (d, c) -> c.awaitCancellation(d), metrics);
    }

    /**
     * 검색(MCP 프로세스: 질의 임베딩·재정렬)은 TS처럼 1회 시도 + timeout — 재시도·Retry-After 대기 없이 실패를 바로 돌려준다(재정렬은 폴백,
     * 질의 임베딩은 오류 — B11). 대화형 호출이 공급자 대기에 묶이지 않는다(불변식 8, ADR-0020 §5).
     */
    @Bean
    @Profile("mcp")
    GeminiCallPolicy interactiveGeminiCalls(GeminiProperties props) {
      return new GeminiCallPolicy(
          new ProviderCallPolicy(props.timeout(), 1, props.timeout(), Math::random));
    }

    /**
     * 수집·투영 명령(데몬 포함)은 재시도: 시도 timeout = scos.gemini.timeout(기본 30s), 최대 3회, 총 2분 — 투영은 자체 재시도
     * (ProjectionRetryPolicy)가 있으므로 F7 기본(5회·600s)보다 짧게.
     */
    @Bean
    @Profile("!mcp")
    GeminiCallPolicy batchGeminiCalls(GeminiProperties props) {
      return new GeminiCallPolicy(
          new ProviderCallPolicy(props.timeout(), 3, Duration.ofMinutes(2), Math::random));
    }

    @Bean
    Embedder geminiEmbedder(
        GeminiProperties props,
        GeminiCallPolicy calls,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return new GeminiEmbedder(
          props.baseUrl(), props.apiKey(), executor(calls, clock, metrics), json);
    }

    /**
     * 재정렬 공급자(TS SCOS_RERANK_PROVIDER): gemini면 생성기 빈, none이면 빈 없음(검색 조립이 비활성 정책으로 받는다). 다른 값은 기동
     * 거부.
     */
    @Bean
    @ConditionalOnProperty(
        name = "scos.rerank.provider",
        havingValue = "gemini",
        matchIfMissing = true)
    TextGenerator geminiTextGenerator(
        GeminiProperties props,
        GeminiCallPolicy calls,
        RerankProperties rerank,
        Clock clock,
        ProviderCallMetrics metrics,
        ObjectMapper json) {
      return new GeminiTextGenerator(
          props.baseUrl(), props.apiKey(), rerank.model(), executor(calls, clock, metrics), json);
    }
  }

  /** Gemini 호출 정책 — 프로세스 용도(검색 대 수집·투영)에 따라 하나가 조립된다. */
  public record GeminiCallPolicy(ProviderCallPolicy policy) {}

  @Validated
  @ConfigurationProperties("scos.gemini")
  public record GeminiProperties(@NotBlank String apiKey, String baseUrl, Duration timeout) {
    public GeminiProperties {
      baseUrl =
          baseUrl == null || baseUrl.isBlank()
              ? "https://generativelanguage.googleapis.com"
              : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
  }
}
