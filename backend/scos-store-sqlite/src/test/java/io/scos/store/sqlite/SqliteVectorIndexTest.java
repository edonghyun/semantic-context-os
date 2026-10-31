package io.scos.store.sqlite;

import io.scos.spi.access.AclStore;
import io.scos.spi.store.VectorIndex;
import io.scos.spi.store.VectorIndexContract;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("벡터 색인 계약 — SQLite 포트(정확 코사인)")
class SqliteVectorIndexTest extends VectorIndexContract {
  @TempDir Path dir;

  private SqliteIndex sqlite;

  @Override
  protected VectorIndex newIndex() {
    sqlite = SqliteIndex.open(dir.resolve("index.sqlite"));
    return sqlite.vectors();
  }

  @Override
  protected AclStore aclStore() {
    return sqlite.acl();
  }
}
