package io.scos.server;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 진단 명령은 미적용 스키마를 확인만 하고 변경하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("access-explain")
class AccessExplainConfiguration {
  @Bean
  FlywayMigrationStrategy accessExplainValidateOnly() {
    return flyway -> flyway.validate();
  }
}
