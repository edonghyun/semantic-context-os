package io.scos.store.sqlite;

import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitMaintenanceContract;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("유지보수 포트 계약 — SQLite 포트")
class SqliteUnitMaintenanceTest extends UnitMaintenanceContract {
  @TempDir Path dir;

  private SqliteIndex sqlite;

  @Override
  protected UnitMaintenance newMaintenance() {
    sqlite = SqliteIndex.open(dir.resolve("index.sqlite"));
    return sqlite.maintenance();
  }

  @Override
  protected UnitStore unitStore() {
    return sqlite.units();
  }

  @Override
  protected VectorIndex vectorIndex() {
    return sqlite.vectors();
  }
}
