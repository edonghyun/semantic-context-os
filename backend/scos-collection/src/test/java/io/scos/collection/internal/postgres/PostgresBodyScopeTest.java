package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.BodyCompletionContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 본문 완료 유스케이스 계약")
class PostgresBodyScopeTest extends BodyCompletionContract {
  @Override
  protected void prepareStores() {
    PostgresSupport.truncateAll();
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    bodies = new PostgresBodyCompletionStore(jdbc);
    raw = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).rawItems();
  }
}
