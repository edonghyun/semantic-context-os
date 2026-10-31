package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.ConfigurationFenceContract;
import io.scos.collection.internal.ConfigurationFenceScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 설정 fencing 계약")
class PostgresConfigurationFenceTest extends ConfigurationFenceContract {
  @Override
  protected ConfigurationFenceScenario newContext() {
    PostgresSupport.truncateAll();
    return new ConfigurationFenceScenario(
        CommitContext.Ports.postgres(PostgresSupport.dataSource()),
        new PostgresBodyCompletionStore(JdbcClient.create(PostgresSupport.dataSource())));
  }
}
