package io.scos.mcp;

import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.AccessParts;
import io.scos.boot.McpConfiguration;
import io.scos.boot.MetricsConfiguration;
import io.scos.boot.ProvidersConfiguration;
import io.scos.engine.support.call.ProviderCallPolicies;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Recorded;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * 검색 공급자 호출 시험의 컨텍스트: 메모리 저장소 위의 MCP 조립(스타터 구성 그대로)과 가짜 Gemini. 질의어가 맞는 유닛 2개(재정렬이 돌 만큼)를 두고
 * search_context를 한 번 부른다. 닫으면 미등록 요청이 없는지 본다.
 */
final class McpSearchAssembly implements AutoCloseable {
  static final SpaceId SPACE = new SpaceId("legacy");
  static final String EMBED = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  static final String GENERATE = "/v1beta/models/gemini-2.5-flash:generateContent";

  /** 검색 호출 hang 방지 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(20);

  /** 가짜 임베딩 응답의 차원(Gemini 기본). */
  static final int DIMENSIONS = 768;

  static final ObjectMapper JSON = new ObjectMapper();

  @Configuration(proxyBeanMethods = false)
  static class MemoryEngine {
    @Bean
    MemoryUnitStore unitStore() {
      return new MemoryUnitStore();
    }

    @Bean
    UnitMaintenance unitMaintenance(MemoryUnitStore units, MemoryVectorIndex vectors) {
      return new MemoryUnitMaintenance(units, vectors);
    }

    @Bean
    AccessParts accessParts(UnitMaintenance maintenance) {
      return new AccessParts.Unrestricted(maintenance);
    }

    @Bean
    KeywordIndex keywordIndex() {
      return new MemoryKeywordIndex();
    }

    @Bean
    MemoryVectorIndex vectorIndex() {
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

  final FakeHttpServer gemini;

  McpSearchAssembly() throws IOException {
    this.gemini = new FakeHttpServer();
  }

  ApplicationContextRunner assembly(String profile) {
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

  /** 질의 임베딩은 성공한다(입력마다 같은 벡터). */
  void embeddingsSucceed() {
    gemini.on("POST", EMBED, r -> FakeHttpServer.ok(vectors(r)));
  }

  /** MCP 조립에서 search_context를 한 번 부른 응답 JSON. */
  JsonNode searchOnce() {
    AtomicReference<JsonNode> result = new AtomicReference<>();
    assembly("mcp")
        .run(
            ctx -> {
              givenTwoMatchingUnits(ctx);
              result.set(search(ctx));
            });
    return result.get();
  }

  /** MCP 조립에서 search_context를 한 번 부르고, 도구가 던진 실패. */
  Throwable searchFailure() {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    assembly("mcp")
        .run(
            ctx -> {
              givenTwoMatchingUnits(ctx);
              failure.set(catchThrowable(() -> search(ctx)));
            });
    return failure.get() instanceof ExecutionException e ? e.getCause() : failure.get();
  }

  /** 이 프로필(과 추가 속성)의 조립이 주는 임베딩 호출 정책. */
  ProviderCallPolicy embeddingPolicy(String profile, String... properties) {
    AtomicReference<ProviderCallPolicy> policy = new AtomicReference<>();
    assembly(profile)
        .withPropertyValues(properties)
        .run(ctx -> policy.set(ctx.getBean(ProviderCallPolicies.class).embedding()));
    return policy.get();
  }

  long embedCalls() {
    return gemini.received().stream().map(Recorded::path).filter(EMBED::equals).count();
  }

  long generateCalls() {
    return gemini.received().stream().map(Recorded::path).filter(GENERATE::equals).count();
  }

  @Override
  public void close() {
    gemini.close();
  }

  /** 재정렬이 돌 만큼(후보 2개 이상) 질의어를 담은 유닛. */
  private static void givenTwoMatchingUnits(AssertableApplicationContext ctx) {
    List<ContextUnit> units =
        List.of(
            ContextUnits.unit("a").inDocument("doc-a").content("정산 배치 규정").build(),
            ContextUnits.unit("b").inDocument("doc-b").content("정산 마감 절차").build());
    ctx.getBean(UnitMaintenance.class).upsert(SPACE, units);
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

  private static JsonNode search(AssertableApplicationContext ctx) throws Exception {
    McpTools tools = ctx.getBean(McpTools.class);
    String response =
        CompletableFuture.supplyAsync(
                () -> tools.searchContext(Principal.singleUser(), Map.of("query", "정산")))
            .get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    return JSON.readTree(response);
  }

  private static String vectors(Recorded request) {
    int n = JSON.readTree(request.body()).get("requests").size();
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < DIMENSIONS; d++) sb.append(d > 0 ? "," : "").append("0.1");
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }
}
