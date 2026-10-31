package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WAL 경합을 주입한 실제 드라이버도 같은 마이그레이션 계약을 지킨다(B4)")
class SqliteWalContentionTest extends SqliteMigrationsContract {
  private WalContentionDataSource contention;

  @Override
  MigrationScenario scenario(Path dir) {
    contention = new WalContentionDataSource();
    contention.failNextWal();
    return new MigrationScenario(dir, contention);
  }

  @Test
  @DisplayName("WAL 전환이 busy로 한 번 실패해도 재시도해 스키마를 적용한다")
  void retriesBusyWal(@TempDir Path dir) throws SQLException {
    // given
    var scenario = scenario(dir);
    // when
    scenario.apply("db/migrations/ordered");
    // then
    assertThat(contention.walFailures()).isEqualTo(1);
    assertThat(scenario.value("PRAGMA journal_mode")).isEqualTo("wal");
    assertThat(scenario.value("SELECT value FROM example")).isEqualTo("tenth");
  }
}
