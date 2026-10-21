package io.scos.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingRegistryConfig;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.projection.ProjectionMetrics;
import io.scos.engine.support.call.ProviderCallMetrics;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 지표 조립(ADR-0009·ADR-0020 §3): 모듈별 지표 객체는 하나의 MeterRegistry를 공유한다. daemon 프로필은 Actuator가 레지스트리를 주고
 * HTTP로 노출하며, mcp 프로필은 stdout 보호를 위해 LoggingMeterRegistry(stderr 로그, 60s)만 쓴다. 그 밖의 명령은 Boot가 주는
 * SimpleMeterRegistry(노출 없음) — 레지스트리는 항상 하나 있고 지표 객체는 그것을 주입받는다(대체 레지스트리를 만들지 않는다).
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
  @Bean
  @Profile("mcp")
  MeterRegistry loggingMeterRegistry() {
    LoggingRegistryConfig config =
        new LoggingRegistryConfig() {
          @Override
          public String get(String key) {
            return null;
          }

          @Override
          public Duration step() {
            return Duration.ofSeconds(60);
          }
        };
    return LoggingMeterRegistry.builder(config)
        .loggingSink(LoggerFactory.getLogger("scos.metrics")::info)
        .build();
  }

  @Bean
  CollectionMetrics collectionMetrics(MeterRegistry registry) {
    return CollectionMetrics.of(registry);
  }

  @Bean
  ProviderCallMetrics providerCallMetrics(MeterRegistry registry) {
    return ProviderCallMetrics.of(registry);
  }

  @Bean
  ProjectionMetrics projectionMetrics(MeterRegistry registry) {
    return ProjectionMetrics.of(registry);
  }
}
