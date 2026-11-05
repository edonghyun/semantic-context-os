package io.scos.store.postgres;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.DocumentOwnershipContract;
import io.scos.spi.store.UnitMaintenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("PostgreSQL 문서 소유는 공통 계약을 지킨다")
class PostgresDocumentOwnershipTest extends DocumentOwnershipContract {
  private final PostgresIndexConfiguration configuration = new PostgresIndexConfiguration();

  @Override
  protected DocumentOwnership newOwnership() {
    PostgresSupport.truncateAll();
    return configuration.newDocumentOwnership(PostgresSupport.dataSource());
  }

  @Override
  protected UnitMaintenance maintenance() {
    return configuration.newUnitMaintenance(PostgresSupport.dataSource());
  }
}
