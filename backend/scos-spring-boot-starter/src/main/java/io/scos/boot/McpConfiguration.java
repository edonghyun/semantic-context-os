package io.scos.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.scos.engine.ContextEngine;
import io.scos.engine.ContextEngineBuilder;
import io.scos.mcp.JsonlQueryLog;
import io.scos.mcp.McpStdioServer;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 모듈 조립 — 공간은 설정(SCOS_SPACE_ID, application.yml 기본 legacy)으로 한 번 바인딩되고 모든 도구가 그 공간만
 * 본다(CONTRACTS A1). Embedder는 필수다 — 없으면 기동 실패(B11: 키워드 전용 저하 없음, 리뷰 I-2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpConfiguration.McpProperties.class)
public class McpConfiguration {
  @Validated
  @ConfigurationProperties("scos.mcp")
  public record McpProperties(@NotBlank String spaceId, String queryLog) {}

  static final String SEARCH_LEXICAL_EXECUTOR = "searchLexicalExecutor";

  /**
   * 검색 레시피의 키워드 채널 전용 실행기(키워드 ∥ 임베딩, DESIGN-PATTERNS §4 — 공용 ForkJoin 금지). stdio 요청은 한 번에 하나라 스레드
   * 둘이면 남는다. 데몬 스레드라 stdin EOF 뒤 프로세스 종료를 막지 않는다.
   */
  @Bean(name = SEARCH_LEXICAL_EXECUTOR, destroyMethod = "shutdownNow")
  ExecutorService searchLexicalExecutor() {
    AtomicInteger n = new AtomicInteger();
    return Executors.newFixedThreadPool(
        2,
        r -> {
          Thread t = new Thread(r, "search-lexical-" + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }

  /** 질의 로그(TS `SCOS_QUERY_LOG`): 경로가 있으면 그 파일에 JSONL을 덧붙이고, 비거나 없으면 끈다(TS 미지정 시 비활성). */
  @Bean
  QueryLog queryLog(McpProperties props, Clock clock) {
    String path = props.queryLog();
    return path == null || path.isBlank() ? QueryLog.NONE : new JsonlQueryLog(Path.of(path), clock);
  }

  /**
   * 읽기 파사드. 지표는 앱 레지스트리에, 재정렬 프롬프트·캐시 키는 앱 매퍼로 만든다. 재정렬은 설정이 켜면 생성기 빈이 **있어야** 한다(없으면 기동 실패) — 조용한
   * 비활성 전환 금지(B11·DESIGN-PATTERNS §7).
   */
  @Bean
  ContextEngine contextEngine(
      McpProperties props,
      RerankProperties rerank,
      ObjectProvider<TextGenerator> generators,
      UnitStore units,
      KeywordIndex keywords,
      VectorIndex vectors,
      Embedder embedder,
      Clock clock,
      ObjectMapper mapper,
      MeterRegistry registry,
      @Qualifier(SEARCH_LEXICAL_EXECUTOR) ExecutorService lexicalExecutor) {
    ContextEngineBuilder builder =
        ContextEngine.builder()
            .units(units)
            .keywords(keywords)
            .vectors(vectors)
            .embedder(embedder)
            .defaultSpace(new SpaceId(props.spaceId()))
            .clock(clock)
            .meterRegistry(registry)
            .lexicalExecutor(lexicalExecutor);
    if (rerank.enabled()) {
      TextGenerator generator = generators.getIfAvailable();
      if (generator == null)
        throw new IllegalStateException(
            "scos.rerank.provider=" + rerank.provider() + " 인데 TextGenerator 빈이 없습니다");
      builder.reranker(generator, mapper);
    }
    return builder.build();
  }

  @Bean
  McpTools mcpTools(ContextEngine engine, ObjectMapper mapper, QueryLog queryLog) {
    return new McpTools(engine, mapper, queryLog);
  }

  @Bean
  McpStdioServer mcpStdioServer(McpTools tools) {
    return new McpStdioServer(tools);
  }
}
