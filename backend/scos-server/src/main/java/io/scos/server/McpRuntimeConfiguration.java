package io.scos.server;

import io.scos.boot.AccessProperties;
import io.scos.boot.McpConfiguration.McpProperties;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.mcp.McpStdioServer;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * `mcp` 실행 모드의 조립(CONTRACTS A6, 리뷰 I-1·M-4): Flyway는 **validate만**(적용 안 된 마이그레이션이 있으면 기동 거부,
 * migrate 없음), 연결은 읽기 전용(application-mcp.yml), stdio 서버는 컨텍스트 수명(SmartLifecycle)으로 시작·정지(SIGTERM
 * 경로에서도 close). 바인딩된 공간의 임베딩 세대가 설정 임베더와 다르면 stdio를 열기 전에 기동을 거부한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mcp")
class McpRuntimeConfiguration {
  @Bean
  SmartInitializingSingleton mcpStdioAccessCheck(AccessProperties access) {
    return () -> AccessModeChecks.refuseStdio(access);
  }

  @Bean
  FlywayMigrationStrategy validateOnly() {
    return flyway -> flyway.validate();
  }

  /**
   * 기동 검사(stage2 §3.3): 공간({@code scos.mcp.space-id})에 저장된 세대가 설정 임베더의 세대와 다르거나 재임베딩이 끝나지 않았으면 종료
   * 코드 1 — 검색이 조용히 0건이 되는 대신 {@code scos reembed}를 안내한다.
   */
  @Bean
  SmartInitializingSingleton mcpEmbeddingGenerationCheck(
      McpProperties props, SpaceMeta meta, Embedder embedder) {
    return () ->
        EmbeddingGenerationCheck.verify(
            meta,
            List.of(new SpaceId(props.spaceId())),
            EmbeddingGenerationCheck.generationOf(embedder));
  }

  @Bean
  SmartLifecycle mcpStdioLifecycle(McpStdioServer server) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        server.start();
        running = true;
      }

      @Override
      public void stop() {
        server.stop();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
