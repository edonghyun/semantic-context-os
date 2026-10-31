package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitMaintenanceContract;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("db")
@DisplayName("유지보수 포트 계약 — PostgreSQL 판(CONTRACTS P)")
class PostgresUnitMaintenanceTest extends UnitMaintenanceContract {
  private PostgresUnitStore units;
  private PostgresVectorIndex vectors;

  @Override
  protected UnitMaintenance newMaintenance() {
    PostgresSupport.truncateAll();
    units = new PostgresUnitStore(new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
    vectors = new PostgresVectorIndex(new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
    return new PostgresUnitMaintenance(units, vectors);
  }

  @Override
  protected UnitStore unitStore() {
    return units;
  }

  @Override
  protected VectorIndex vectorIndex() {
    return vectors;
  }
}
