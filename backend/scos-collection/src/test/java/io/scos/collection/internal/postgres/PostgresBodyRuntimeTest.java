package io.scos.collection.internal.postgres;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.BodyRuntimeScenario;
import io.scos.collection.internal.BodyRuntimeStateContract;
import io.scos.collection.internal.CommitContext;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 런타임의 본문 요약 계약")
class PostgresBodyRuntimeTest extends BodyRuntimeStateContract {
  @Override
  protected BodyRuntimeScenario newScenario() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    var bodies = new PostgresBodyCompletionStore(JdbcClient.create(ds));
    return new BodyRuntimeScenario(
        CommitContext.Ports.postgres(ds),
        bodies,
        adapter ->
            PostgresCollectionRuntime.over(ds, new MutableClock(BodyCommitScenario.NOW))
                .adapter(adapter)
                .build());
  }
}
