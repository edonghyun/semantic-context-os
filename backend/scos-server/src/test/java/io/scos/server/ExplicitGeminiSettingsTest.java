package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.boot.McpConfiguration;
import io.scos.boot.ProjectionConfiguration;
import io.scos.boot.ProvidersConfiguration;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicies;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.provider.gemini.GeminiEmbedder;
import io.scos.provider.gemini.GeminiTextGenerator;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.TextGenerator;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini를 명시한 운영 설정의 재현(공개 배포 단계 5 T3·T7 특성화): server의 실제 application.yml을 읽고 환경변수 이름을 속성으로 준다. 공개
 * 기본값이 바뀌어도 이 키들을 준 운영은 같은 임베더·생성기·호출 정책·공간·DB 버전 검사로 돈다. 슬라이스, DB 없음.
 */
@DisplayName("Gemini를 명시한 설정은 지금과 같은 공급자·정책·공간으로 조립된다")
class ExplicitGeminiSettingsTest {
  static final Duration GEMINI_TIMEOUT = Duration.ofSeconds(30);
  static final int BATCH_ATTEMPTS = 3;
  static final Duration BATCH_BUDGET = Duration.ofMinutes(2);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    McpConfiguration.McpProperties.class,
    ProjectionConfiguration.ProjectionProperties.class,
    OpsConfiguration.DatabaseProperties.class
  })
  static class SpaceAndDatabaseSettings {}

  @Nested
  @DisplayName("Gemini를 명시한 설정(O1 키)을 주면")
  class ExplicitGemini {
    final ApplicationContextRunner settings =
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ProviderCallMetrics.class, TestMetrics::providerCalls)
            .withUserConfiguration(SpaceAndDatabaseSettings.class, ProvidersConfiguration.class)
            .withPropertyValues(
                "SCOS_SPACE_ID=legacy",
                "SCOS_EMBEDDER_PROVIDER=gemini",
                "SCOS_RERANK_PROVIDER=gemini",
                "SCOS_RERANK_MODEL=gemini-2.5-flash",
                "SCOS_DB_EXPECTED_SERVER_VERSION=17.11",
                "SCOS_DB_EXPECTED_VECTOR_VERSION=0.8.7",
                "GEMINI_API_KEY=k");

    /** 두 역할의 정책: (시도 timeout, 시도 횟수, 총 예산). */
    static List<ProviderCallPolicy> bothRoles(ProviderCallPolicies policies) {
      return List.of(policies.embedding(), policies.rerank().orElseThrow());
    }

    @Test
    @DisplayName("임베더는 gemini·gemini-embedding-001·768인 GeminiEmbedder다")
    void geminiEmbedder() {
      // when·then
      settings.run(
          ctx ->
              assertThat(ctx.getBean(Embedder.class))
                  .isInstanceOf(GeminiEmbedder.class)
                  .extracting(Embedder::identity)
                  .isEqualTo(new EmbedderIdentity("gemini", "gemini-embedding-001", 768)));
    }

    @Test
    @DisplayName("재정렬 생성기는 GeminiTextGenerator다")
    void geminiTextGenerator() {
      // when·then
      settings.run(
          ctx ->
              assertThat(ctx.getBean(TextGenerator.class)).isInstanceOf(GeminiTextGenerator.class));
    }

    @Test
    @DisplayName("mcp 프로필의 공급자 호출 정책은 1회 시도·30초다")
    void mcpPolicy() {
      // when·then
      settings
          .withPropertyValues("spring.profiles.active=mcp")
          .run(
              ctx ->
                  assertThat(bothRoles(ctx.getBean(ProviderCallPolicies.class)))
                      .extracting(
                          ProviderCallPolicy::requestTimeout,
                          ProviderCallPolicy::maxAttempts,
                          ProviderCallPolicy::maxElapsed)
                      .containsExactly(
                          tuple(GEMINI_TIMEOUT, 1, GEMINI_TIMEOUT),
                          tuple(GEMINI_TIMEOUT, 1, GEMINI_TIMEOUT)));
    }

    @Test
    @DisplayName("일괄 프로필의 공급자 호출 정책은 3회·2분이다")
    void batchPolicy() {
      // when·then
      settings.run(
          ctx ->
              assertThat(bothRoles(ctx.getBean(ProviderCallPolicies.class)))
                  .extracting(
                      ProviderCallPolicy::requestTimeout,
                      ProviderCallPolicy::maxAttempts,
                      ProviderCallPolicy::maxElapsed)
                  .containsExactly(
                      tuple(GEMINI_TIMEOUT, BATCH_ATTEMPTS, BATCH_BUDGET),
                      tuple(GEMINI_TIMEOUT, BATCH_ATTEMPTS, BATCH_BUDGET)));
    }

    @Test
    @DisplayName("MCP 공간과 투영 기본 공간은 legacy다")
    void legacySpaces() {
      // when·then
      settings.run(
          ctx -> {
            assertThat(ctx.getBean(McpConfiguration.McpProperties.class).spaceId())
                .isEqualTo("legacy");
            assertThat(
                    ctx.getBean(ProjectionConfiguration.ProjectionProperties.class).defaultSpace())
                .isEqualTo("legacy");
          });
    }

    @Test
    @DisplayName("DB 버전은 정확 일치로 검사한다")
    void exactDatabaseVersions() {
      // when·then
      settings.run(
          ctx ->
              assertThat(ctx.getBean(OpsConfiguration.DatabaseProperties.class))
                  .isEqualTo(new OpsConfiguration.DatabaseProperties("17.11", "0.8.7")));
    }
  }
}
