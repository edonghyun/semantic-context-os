package io.scos.collection.internal.postgres;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.RuntimeConfigurationFenceContract;
import io.scos.collection.internal.RuntimeConfigurationFenceScenario;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 런타임 설정 fencing 계약")
class PostgresRuntimeConfigurationFenceTest extends RuntimeConfigurationFenceContract {
  @Override
  protected RuntimeConfigurationFenceScenario newContext() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    var bodies = new PostgresBodyCompletionStore(JdbcClient.create(ds));
    return new RuntimeConfigurationFenceScenario(
        CommitContext.Ports.postgres(ds),
        bodies,
        adapter ->
            PostgresCollectionRuntime.over(ds, new MutableClock(BodyCommitScenario.NOW))
                .adapter(adapter)
                .build());
  }
}
