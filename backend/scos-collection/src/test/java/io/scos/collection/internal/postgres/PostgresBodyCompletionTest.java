package io.scos.collection.internal.postgres;

import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.BodyCompletionStoreContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 본문 완료 저장소 계약")
class PostgresBodyCompletionTest extends BodyCompletionStoreContract {
  @Override
  protected BodyCompletionStore newStore() {
    PostgresSupport.truncateAll();
    return new PostgresBodyCompletionStore(JdbcClient.create(PostgresSupport.dataSource()));
  }
}
