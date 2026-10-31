package io.scos.client.collect;

import io.scos.spi.connector.RawItem;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 실제 V1·V2 스크립트로 옛 설치를 만들어 최신 열기와 독립적인 입력을 준다. */
final class StateStoreUpgradeScenario {
  private final Path file;

  StateStoreUpgradeScenario(Path root) {
    file = root.resolve("state.sqlite");
  }

  void givenLegacyJsonContent() throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/sqlite/state/V1__init.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/sqlite/state/V2__reproject_source_times.sql"));
      statement.execute(
          """
          INSERT INTO raw_items (source, external_id, document_id, resource_type,
                                 source_version, source_updated_at, observed_at, content)
          VALUES ('claude-memory', 'd/a.md', 'memory/d/a.md', 'claude.memory.file',
                  'v1', '2026-10-27T10:00:00.000Z', '2026-10-28T00:00:00Z',
                  CAST('{"text":"합성 메모"}' AS BLOB))
          """);
      statement.execute("PRAGMA user_version = 2");
    }
  }

  void givenNewerSchema() throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = 999");
    }
  }

  void open() {
    try (StateStore ignored = StateStore.open(file)) {
      // 새 버전은 저장소를 반환하기 전에 거부해야 한다.
    }
  }

  List<RawItem> reopenItems() {
    try (StateStore store = StateStore.open(file)) {
      return store.items("claude-memory", Set.of("memory/d/a.md"), Set.of("claude.memory.file"));
    }
  }
}
