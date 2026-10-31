package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.IdentityStoreContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 신원 저장소는 공통 접근 키 계약을 지킨다")
class PostgresIdentityStoreTest extends IdentityStoreContract {
  @Override
  protected IdentityStore newStore() {
    PostgresSupport.truncateAll();
    return new PostgresIdentityStore(JdbcClient.create(PostgresSupport.dataSource()));
  }

  @Override
  protected void seed(AccessDirectory directory) {
    new PostgresAccessSeed(PostgresSupport.dataSource()).seed(directory);
  }
}
