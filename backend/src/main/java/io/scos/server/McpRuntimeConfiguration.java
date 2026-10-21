package io.scos.server;

import io.scos.mcp.McpStdioServer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * `mcp` 실행 모드의 조립(CONTRACTS A6, 리뷰 I-1·M-4): Flyway는 **validate만**(적용 안 된 마이그레이션이 있으면 기동 거부,
 * migrate 없음), 연결은 읽기 전용(application-mcp.yml), stdio 서버는 컨텍스트 수명(SmartLifecycle)으로 시작·정지(SIGTERM
 * 경로에서도 close).
 */
@Configuration(proxyBeanMethods = false)
@Profile("mcp")
class McpRuntimeConfiguration {
  @Bean
  FlywayMigrationStrategy validateOnly() {
    return flyway -> flyway.validate();
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
