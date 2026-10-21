package io.scos.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.RerankProperties;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MCP 조립 슬라이스(DB 없음)의 메모리 엔진: 메모리 색인·0 벡터 임베더(dim 2)·단순 레지스트리. 지표·질의 로그 배선 테스트가 함께 쓴다.
 * {@code @Configuration}을 붙이지 않는다(@Bean lite 모드) — 최상위 클래스라 붙이면 제품 컴포넌트 스캔(ScosApplication을 띄우는
 * 명령·MCP 테스트)에 잡혀 Postgres 빈과 충돌한다.
 */
@EnableConfigurationProperties(RerankProperties.class)
class McpMemoryEngine {
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
  Embedder embedder() {
    return FixedEmbedder.zero(2);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
