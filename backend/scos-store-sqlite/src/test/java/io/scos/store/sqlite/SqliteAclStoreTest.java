package io.scos.store.sqlite;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.AclStoreContract;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ACL 저장소 계약 — SQLite 포트")
class SqliteAclStoreTest extends AclStoreContract {
  @TempDir Path dir;

  @Override
  protected AclStore newStore() {
    return SqliteIndex.open(dir.resolve("index.sqlite")).acl();
  }
}
