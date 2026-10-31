package io.scos.store.sqlite;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;

@DisplayName("실제 SQLite 드라이버는 마이그레이션 계약을 지킨다(B4)")
class SqliteMigrationsTest extends SqliteMigrationsContract {
  @Override
  MigrationScenario scenario(Path dir) {
    return new MigrationScenario(dir);
  }
}
