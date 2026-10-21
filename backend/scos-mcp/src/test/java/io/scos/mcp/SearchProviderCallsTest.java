package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.McpConfiguration;
import io.scos.boot.MetricsConfiguration;
import io.scos.boot.ProvidersConfiguration;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 검색(MCP) 경로의 공급자 호출은 TS처럼 한 번 시도한다(ADR-0020 §5): 재정렬이 429를 받으면 Retry-After를 기다리지 않고 바로 폴백한다. 질의
 * 임베딩이 429·5xx면 폴백 없이 "Gemini embeddings <상태>" 도구 오류로 끝난다(TS 임베더 단발 fetch + SDK isError). 수집·투영 명령의
 * 재시도 정책은 그대로다. Gemini는 로컬 fake HTTP.
 */
@DisplayName("검색(MCP) 경로의 공급자 호출은 한 번만 시도한다(ADR-0020 §5)")
class SearchProviderCallsTest {
  /** MCP 정책 사례가 주는 Gemini 시도 timeout(기본 30초와 다른 값). */
  static final Duration GEMINI_TIMEOUT = Duration.ofSeconds(7);

  static final SpaceId SPACE = new SpaceId("legacy");
  static final String EMBED = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  static final String GENERATE = "/v1beta/models/gemini-2.5-flash:generateContent";
  static final long RETRY_AFTER_SECONDS = 60;

  /** 검색 호출 hang 방지 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(20);

  static final int BATCH_ATTEMPTS = 3;
  static final Duration BATCH_BUDGET = Duration.ofMinutes(2);
  static final ObjectMapper JSON = new ObjectMapper();

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

    /** mcp 프로필은 MetricsConfiguration의 LoggingMeterRegistry를 쓴다(Boot 자동 구성 대신). */
    @Bean
    @Profile("!mcp")
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  FakeHttpServer gemini;

  @BeforeEach
  void startGemini() throws Exception {
    gemini = new FakeHttpServer();
  }

  @AfterEach
  void stopGemini() {
    gemini.assertNoUnmatched();
    gemini.close();
  }

  ApplicationContextRunner geminiAssembly(String profile) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(
            MemoryEngine.class,
            MetricsConfiguration.class,
            ProvidersConfiguration.class,
            McpConfiguration.class)
        .withPropertyValues(
            "spring.profiles.active=" + profile,
            "scos.mcp.space-id=" + SPACE.value(),
            "scos.gemini.api-key=test-key",
            "scos.gemini.base-url=" + gemini.baseUrl(),
            "scos.rerank.provider=gemini");
  }

  void givenRerankRateLimited() {
    gemini.on("POST", EMBED, r -> FakeHttpServer.ok(vectors(r)));
    gemini.on("POST", GENERATE, r -> FakeHttpServer.rateLimited(RETRY_AFTER_SECONDS));
  }

  /** 재정렬이 돌 만큼(후보 2개 이상) 질의어를 담은 유닛. */
  static void givenTwoMatchingUnits(AssertableApplicationContext ctx) {
    List<ContextUnit> units =
        List.of(
            ContextUnits.unit("a").inDocument("doc-a").content("정산 배치 규정").build(),
            ContextUnits.unit("b").inDocument("doc-b").content("정산 마감 절차").build());
    ctx.getBean(UnitStore.class).upsert(SPACE, units);
    ctx.getBean(KeywordIndex.class)
        .upsert(
            SPACE,
            units.stream()
                .map(
                    u ->
                        new IndexedText(
                            u.id(), u.documentId(), u.title(), u.content(), u.partition(), false))
                .toList());
  }

  static JsonNode whenSearching(AssertableApplicationContext ctx) throws Exception {
    McpTools tools = ctx.getBean(McpTools.class);
    String response =
        CompletableFuture.supplyAsync(() -> tools.searchContext(Map.of("query", "정산")))
            .get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    return JSON.readTree(response);
  }

  long embedCalls() {
    return gemini.received().stream().map(Recorded::path).filter(EMBED::equals).count();
  }

  long generateCalls() {
    return gemini.received().stream().map(Recorded::path).filter(GENERATE::equals).count();
  }

  static String vectors(Recorded request) {
    int n = JSON.readTree(request.body()).get("requests").size();
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < 768; d++) sb.append(d > 0 ? "," : "").append("0.1");
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }

  /** MCP 조립에서 질의어가 맞는 유닛 2개를 두고 search_context를 한 번 부른다. */
  JsonNode searchTwoMatchingUnitsThroughMcp() {
    AtomicReference<JsonNode> result = new AtomicReference<>();
    geminiAssembly("mcp")
        .run(
            ctx -> {
              givenTwoMatchingUnits(ctx);
              result.set(whenSearching(ctx));
            });
    return result.get();
  }

  /** MCP 조립에서 질의어가 맞는 유닛 2개를 두고 search_context를 한 번 부르고, 도구가 던진 실패를 돌려준다. */
  Throwable searchTwoMatchingUnitsThroughMcpFails() {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    geminiAssembly("mcp")
        .run(
            ctx -> {
              givenTwoMatchingUnits(ctx);
              failure.set(catchThrowable(() -> whenSearching(ctx)));
            });
    return failure.get() instanceof ExecutionException e ? e.getCause() : failure.get();
  }

  @Nested
  @DisplayName("MCP에서 질의 임베딩이 429(Retry-After 60s)를 받으면")
  class QueryEmbeddingRateLimited {
    @BeforeEach
    void rateLimited() {
      gemini.on("POST", EMBED, r -> FakeHttpServer.rateLimited(RETRY_AFTER_SECONDS));
    }

    @Test
    @DisplayName("키워드 결과로 낮추지 않고 'Gemini embeddings 429' 도구 오류(isError)로 끝난다")
    void failsWithToolError() {
      // when
      Throwable failure = searchTwoMatchingUnitsThroughMcpFails();

      // then
      assertThat(failure)
          .isInstanceOf(McpTools.ToolError.class)
          .hasMessage("Gemini embeddings 429");
    }

    @Test
    @DisplayName("Retry-After를 기다려 다시 시도하지 않는다")
    void isNotRetried() {
      // when
      searchTwoMatchingUnitsThroughMcpFails();

      // then
      assertThat(embedCalls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("MCP에서 질의 임베딩이 503을 받으면")
  class QueryEmbeddingUnavailable {
    @BeforeEach
    void unavailable() {
      gemini.on("POST", EMBED, r -> FakeHttpServer.status(503, "{}"));
    }

    @Test
    @DisplayName("키워드 결과로 낮추지 않고 'Gemini embeddings 503' 도구 오류(isError)로 끝난다")
    void failsWithToolError() {
      // when
      Throwable failure = searchTwoMatchingUnitsThroughMcpFails();

      // then
      assertThat(failure)
          .isInstanceOf(McpTools.ToolError.class)
          .hasMessage("Gemini embeddings 503");
    }

    @Test
    @DisplayName("다시 시도하지 않는다")
    void isNotRetried() {
      // when
      searchTwoMatchingUnitsThroughMcpFails();

      // then
      assertThat(embedCalls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("MCP에서 재정렬이 429(Retry-After 60s)를 받으면")
  class RerankRateLimited {
    @BeforeEach
    void rateLimited() {
      givenRerankRateLimited();
    }

    @Test
    @DisplayName("재정렬 없이 결과를 돌려준다")
    void rerankRateLimitFallsBack() {
      // when
      JsonNode result = searchTwoMatchingUnitsThroughMcp();

      // then
      assertThat(result.get("hits").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 시도하지 않는다")
    void rerankRateLimitIsNotRetried() {
      // when
      searchTwoMatchingUnitsThroughMcp();

      // then
      assertThat(generateCalls()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("MCP의 Gemini 호출 정책은 1회 시도·총 예산 = 시도 timeout이다")
  void mcpPolicyIsSingleAttempt() {
    // given: 시도 timeout을 7초로 둔다
    AtomicReference<ProviderCallPolicy> policy = new AtomicReference<>();

    // when
    geminiAssembly("mcp")
        .withPropertyValues("scos.gemini.timeout=" + GEMINI_TIMEOUT.toSeconds() + "s")
        .run(
            ctx -> policy.set(ctx.getBean(ProvidersConfiguration.GeminiCallPolicy.class).policy()));

    // then
    assertThat(policy.get())
        .extracting(ProviderCallPolicy::maxAttempts, ProviderCallPolicy::maxElapsed)
        .containsExactly(1, GEMINI_TIMEOUT);
  }

  @Test
  @DisplayName("수집·투영 명령(기본 프로필)의 Gemini 호출 정책은 재시도 3회·총 2분 그대로다")
  void commandPolicyRetries() {
    // given
    AtomicReference<ProviderCallPolicy> policy = new AtomicReference<>();

    // when
    geminiAssembly("default")
        .run(
            ctx -> policy.set(ctx.getBean(ProvidersConfiguration.GeminiCallPolicy.class).policy()));

    // then
    assertThat(policy.get())
        .extracting(ProviderCallPolicy::maxAttempts, ProviderCallPolicy::maxElapsed)
        .containsExactly(BATCH_ATTEMPTS, BATCH_BUDGET);
  }
}
