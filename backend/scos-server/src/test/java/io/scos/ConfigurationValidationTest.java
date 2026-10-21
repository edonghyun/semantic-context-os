package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.CollectionConfiguration;
import io.scos.boot.McpConfiguration;
import io.scos.boot.MetricsConfiguration;
import io.scos.boot.ProjectionConfiguration;
import io.scos.boot.ProvidersConfiguration;
import io.scos.boot.RerankProperties;
import io.scos.harness.HarnessConfiguration;
import io.scos.mcp.McpTools;
import io.scos.server.DaemonConfiguration;
import io.scos.server.ScosApplication;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 잘못된 설정은 기동 실패다(계획 4.1, ADR-0009·I-7): 임베더 부재·Gemini 키 누락·하네스 설정 누락.
 * 슬라이스(ApplicationContextRunner), DB 없음.
 */
@DisplayName("잘못된 설정은 기동 실패다(계획 4.1·ADR-0009·I-7)")
class ConfigurationValidationTest {
  @Configuration(proxyBeanMethods = false)
  static class MemoryEngine {
    @Bean
    UnitStore unitStore() {
      return new MemoryUnitStore();
    }

    @Bean
    KeywordIndex keywordIndex() {
      return new MemoryKeywordIndex();
    }

    @Bean
    VectorIndex vectorIndex() {
      return new MemoryVectorIndex();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    /** Boot 자동 구성이 주는 레지스트리 대신(슬라이스) — 지표 객체는 레지스트리를 주입받는다. */
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ZeroEmbedder {
    @Bean
    Embedder embedder() {
      return FixedEmbedder.zero(2);
    }
  }

  /** providers 모듈의 몫(재정렬 설정: 소유자 하나)을 Gemini 조립 없이 대신 등록한다. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RerankProperties.class)
  static class RerankSettings {}

  /** 클래스가 적재된 디렉터리(작업 디렉터리 기준 상대 경로에 기대지 않는다). */
  static Path classesRoot(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  /** 클래스 위치(디렉터리나 jar)에 그 경로의 항목이 있는가. testFixtures가 있으면 main은 jar로 들어온다. */
  static boolean hasEntry(Path root, String entry) throws Exception {
    if (Files.isDirectory(root)) return Files.exists(root.resolve(entry));
    try (FileSystem jar = FileSystems.newFileSystem(root)) {
      return Files.exists(jar.getPath(entry));
    }
  }

  /** 기동 실패의 근본 원인이 Bean Validation(프로퍼티 이름 포함)인지 — 무관한 이유의 실패를 성공으로 세지 않는다(TESTING §8). */
  static void assertBindValidationFailure(AssertableApplicationContext ctx, String property) {
    assertThat(ctx)
        .getFailure()
        .rootCause()
        .isInstanceOf(BindValidationException.class)
        .hasMessageContaining(property);
  }

  @Nested
  @DisplayName("MCP 조립에서")
  class McpAssembly {
    final ApplicationContextRunner mcp =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(
                MemoryEngine.class,
                RerankSettings.class,
                MetricsConfiguration.class,
                McpConfiguration.class)
            .withPropertyValues("scos.mcp.space-id=legacy", "scos.rerank.provider=none");

    /** Embedder 빈이 있는 MCP 조립(나머지 조건을 하나씩 깨는 기준). */
    final ApplicationContextRunner withEmbedder = mcp.withUserConfiguration(ZeroEmbedder.class);

    @Test
    @DisplayName("Embedder 빈이 없으면 기동에 실패한다(키워드 전용 저하 없음, B11)")
    void mcpRequiresEmbedder() {
      // when·then
      mcp.run(
          ctx ->
              assertThat(ctx)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(NoSuchBeanDefinitionException.class)
                  .hasMessageContaining(Embedder.class.getName()));
    }

    @Test
    @DisplayName("Embedder 빈이 있으면 McpTools를 조립한다")
    void assemblesWithEmbedder() {
      // when·then
      withEmbedder.run(ctx -> assertThat(ctx).hasSingleBean(McpTools.class));
    }

    @Test
    @DisplayName("재정렬이 켜졌는데 TextGenerator 빈이 없으면 기동에 실패한다")
    void rerankRequiresGenerator() {
      // when·then
      withEmbedder
          .withPropertyValues("scos.rerank.provider=gemini")
          .run(
              ctx ->
                  assertThat(ctx)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("TextGenerator 빈이 없습니다"));
    }

    @Test
    @DisplayName("scos.rerank.provider가 none·gemini 밖이면 기동에 실패한다")
    void rejectsUnknownRerankProvider() {
      // when·then
      withEmbedder
          .withPropertyValues("scos.rerank.provider=bogus")
          .run(ctx -> assertBindValidationFailure(ctx, "provider"));
    }

    @Test
    @DisplayName("공간 id가 비면 spaceId 검증 오류로 기동에 실패한다")
    void mcpRequiresSpace() {
      // when·then
      withEmbedder
          .withPropertyValues("scos.mcp.space-id=")
          .run(ctx -> assertBindValidationFailure(ctx, "spaceId"));
    }
  }

  @Nested
  @DisplayName("기본 프로필로 공급자를 조립하면")
  class ProvidersAssembly {
    final ApplicationContextRunner providers =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            // Gemini 호출 실행기는 시계·지표 빈을 요구한다(ADR-0020 §5 I-9)
            .withUserConfiguration(
                MemoryEngine.class, MetricsConfiguration.class, ProvidersConfiguration.class);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"scos.gemini.api-key=", "scos.gemini.timeout=30s"})
    @DisplayName("GEMINI_API_KEY가 비거나 없으면 apiKey 검증 오류로 기동에 실패한다")
    void geminiKeyRequired(String property) {
      // when·then
      providers.withPropertyValues(property).run(ctx -> assertBindValidationFailure(ctx, "apiKey"));
    }

    @Test
    @DisplayName("키가 있으면 Gemini 임베더와 생성기를 조립한다")
    void geminiBeansWithKey() {
      // when·then
      providers
          .withPropertyValues("scos.gemini.api-key=k")
          .run(
              ctx ->
                  assertThat(ctx).hasSingleBean(Embedder.class).hasSingleBean(TextGenerator.class));
    }

    @Test
    @DisplayName("rerank.provider=none이면 생성기는 조립하지 않는다")
    void noGeneratorWithoutRerank() {
      // when·then
      providers
          .withPropertyValues("scos.gemini.api-key=k", "scos.rerank.provider=none")
          .run(
              ctx ->
                  assertThat(ctx)
                      .hasSingleBean(Embedder.class)
                      .doesNotHaveBean(TextGenerator.class));
    }
  }

  @Nested
  @DisplayName("harness 프로필로 조립하면")
  class HarnessAssembly {
    static final String HARNESS_NOW = "2026-10-13T00:00:00Z";

    final ApplicationContextRunner harness =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(HarnessConfiguration.class)
            .withPropertyValues("spring.profiles.active=harness");

    /** 카세트와 고정 시각을 준 harness 프로필. */
    ApplicationContextRunner harnessAt(String now) {
      return harness.withPropertyValues(
          "scos.harness.now=" + now, "scos.harness.cassette=" + cassettePath());
    }

    static String cassettePath() {
      return Path.of(
              System.getProperty("scos.fixtures.dir", "../harness/fixtures/p1"), "cassette.json")
          .toAbsolutePath()
          .toString();
    }

    @Test
    @DisplayName("now가 비면 검증 오류로 기동에 실패한다")
    void harnessRequiresNow() {
      // when·then
      harness
          .withPropertyValues("scos.harness.cassette=" + cassettePath())
          .run(ctx -> assertBindValidationFailure(ctx, "now"));
    }

    @Test
    @DisplayName("cassette가 비면 검증 오류로 기동에 실패한다")
    void harnessRequiresCassette() {
      // when·then
      harness
          .withPropertyValues("scos.harness.now=2026-10-13T00:00:00Z")
          .run(ctx -> assertBindValidationFailure(ctx, "cassette"));
    }

    @Test
    @DisplayName("카세트 Embedder와 Clock을 조립한다(어댑터 JAR)")
    void harnessProfileAssemblesFixedClockAndCassette() {
      // when·then
      harnessAt(HARNESS_NOW)
          .run(ctx -> assertThat(ctx).hasSingleBean(Embedder.class).hasSingleBean(Clock.class));
    }

    @Test
    @DisplayName("Clock은 scos.harness.now에 고정된다")
    void harnessClockIsFixed() {
      // when·then
      harnessAt(HARNESS_NOW)
          .run(
              ctx ->
                  assertThat(ctx.getBean(Clock.class).instant())
                      .isEqualTo(Instant.parse(HARNESS_NOW)));
    }
  }

  @Test
  @DisplayName("제품 bootJar에는 하네스 어댑터(src/harness)가 없다")
  void harnessAdaptersAreNotInProduct() throws Exception {
    // when
    Path mainClasses = classesRoot(ScosApplication.class);
    Path harnessClasses = classesRoot(HarnessConfiguration.class);
    String application = ScosApplication.class.getName().replace('.', '/') + ".class";
    if (!hasEntry(mainClasses, application))
      throw new IllegalStateException(
          "given 준비 실패: 제품 클래스 위치 " + mainClasses + "에 " + application + "이 없다");

    // then
    assertThat(hasEntry(mainClasses, "io/scos/harness")).isFalse();
    assertThat(harnessClasses).isNotEqualTo(mainClasses);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    CollectionConfiguration.CollectionProperties.class,
    ProjectionConfiguration.ProjectionProperties.class
  })
  static class CollectionAndProjectionProperties {}

  @Nested
  @DisplayName("수집·투영 설정을 바인딩하면")
  class CollectionAndProjectionBinding {
    final ApplicationContextRunner properties =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(CollectionAndProjectionProperties.class);

    @Test
    @DisplayName("lease-ttl이 2초 미만이면 leaseTtl 검증 오류로 기동에 실패한다")
    void rejectsTooShortLeaseTtl() {
      // when·then
      properties
          .withPropertyValues("scos.collection.lease-ttl=1s")
          .run(
              ctx ->
                  assertThat(ctx)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(BindValidationException.class)
                      .hasMessageContaining("leaseTtl"));
    }

    @Test
    @DisplayName("lease-ttl이 정확히 2초면 기동한다")
    void acceptsTwoSecondLeaseTtl() {
      // when·then
      properties
          .withPropertyValues("scos.collection.lease-ttl=2s")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("slack-base-url이 http(s) URL이 아니면 기동에 실패한다")
    void rejectsBadSlackBaseUrl() {
      // when·then
      properties
          .withPropertyValues("scos.collection.slack-base-url=slack.com")
          .run(
              ctx ->
                  assertThat(ctx)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(BindValidationException.class)
                      .hasMessageContaining("slackBaseUrl"));
    }

    @Test
    @DisplayName("slack-base-url이 http URL이면 기동한다")
    void acceptsHttpSlackBaseUrl() {
      // when·then
      properties
          .withPropertyValues("scos.collection.slack-base-url=http://127.0.0.1:1")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("projection.default-space가 비면 기동에 실패한다")
    void rejectsBlankDefaultSpace() {
      // when·then
      properties
          .withPropertyValues("scos.projection.default-space=")
          .run(
              ctx ->
                  assertThat(ctx)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(BindValidationException.class)
                      .hasMessageContaining("defaultSpace"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DaemonConfiguration.DaemonProperties.class)
  static class DaemonPropertiesOnly {}

  /** 스냅샷 간격 없음(설정하지 않으면 스냅샷 모드를 끈다). */
  static final Duration NO_SNAPSHOT = null;

  /** 유지보수 유예 기간(일) — TS 기본 30일. */
  static final int GRACE_DAYS = 30;

  /** 15분·poll 5s·재시도 30s~300s·worker 2·스냅샷 없음·종료 10s·유지보수 24h·유예 30일·정체 30분. */
  static final DaemonConfiguration.DaemonProperties DEFAULT_DAEMON =
      new DaemonConfiguration.DaemonProperties(
          Duration.ofMinutes(15),
          Duration.ofSeconds(5),
          Duration.ofSeconds(30),
          Duration.ofMinutes(5),
          2,
          NO_SNAPSHOT,
          Duration.ofSeconds(10),
          Duration.ofHours(24),
          GRACE_DAYS,
          Duration.ofMinutes(30));

  @Nested
  @DisplayName("daemon 설정을 바인딩하면")
  class DaemonBinding {
    final ApplicationContextRunner daemonProperties =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(DaemonPropertiesOnly.class);

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
      "scos.daemon.concurrency=0, concurrency",
      "scos.daemon.poll=50ms, poll",
      "scos.daemon.interval=0s, interval",
      "scos.daemon.retry-base=0s, retryBase",
      "scos.daemon.shutdown-timeout=0s, shutdownTimeout",
      "scos.daemon.maintenance-interval=0s, maintenanceInterval",
      "scos.daemon.maintenance-grace-days=-1, maintenanceGraceDays",
      "scos.daemon.snapshot-interval=0s, snapshotInterval",
      "scos.daemon.stall-after=0s, stallAfter"
    })
    @DisplayName("값이 범위를 벗어나면 그 프로퍼티의 검증 오류로 기동에 실패한다")
    void rejectsOutOfRangeDaemonProperty(String property, String field) {
      // when·then
      daemonProperties
          .withPropertyValues(property)
          .run(ctx -> assertBindValidationFailure(ctx, field));
    }

    @Test
    @DisplayName("재시도 창이 뒤집히면(retry-base > retry-max) 바인딩 검증 오류로 실패한다")
    void rejectsInvertedRetryWindow() {
      // when·then
      daemonProperties
          .withPropertyValues("scos.daemon.retry-base=300s", "scos.daemon.retry-max=30s")
          .run(ctx -> assertBindValidationFailure(ctx, "retryBaseAtMostRetryMax"));
    }

    @Test
    @DisplayName("주지 않으면 문서화된 기본값으로 바인딩된다")
    void daemonDefaultsBind() {
      // when·then
      daemonProperties.run(
          ctx ->
              assertThat(ctx.getBean(DaemonConfiguration.DaemonProperties.class))
                  .isEqualTo(DEFAULT_DAEMON));
    }
  }
}
