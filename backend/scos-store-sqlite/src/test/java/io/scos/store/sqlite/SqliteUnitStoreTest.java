package io.scos.store.sqlite;

import io.scos.spi.access.AclStore;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.UnitStoreContract;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("유닛 저장소 계약 — SQLite 포트")
class SqliteUnitStoreTest extends UnitStoreContract {
  @TempDir Path dir;

  private SqliteIndex index;

  @Override
  protected UnitStore newStore() {
    index = SqliteIndex.open(dir.resolve("index.sqlite"));
    return index.units();
  }

  @Override
  protected AclStore aclStore() {
    return index.acl();
  }

  @Override
  protected UnitMaintenance maintenance() {
    return index.maintenance();
  }
}
