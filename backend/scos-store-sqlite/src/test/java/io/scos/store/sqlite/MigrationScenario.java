package io.scos.store.sqlite;

import java.nio.file.Path;
import java.sql.SQLException;
import org.sqlite.SQLiteDataSource;

final class MigrationScenario {
  private final SQLiteDataSource dataSource;

  MigrationScenario(Path dir) {
    this(dir, new SQLiteDataSource());
  }

  MigrationScenario(Path dir, SQLiteDataSource dataSource) {
    this.dataSource = dataSource;
    dataSource.setUrl("jdbc:sqlite:" + dir.resolve("migration.sqlite"));
  }

  boolean apply(String resources) throws SQLException {
    return new SqliteMigrations(dataSource, resources, "migration.sqlite").apply();
  }

  void givenVersionOne() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE example (value TEXT)");
      statement.execute("INSERT INTO example VALUES ('first')");
      statement.execute("PRAGMA user_version = 1");
    }
  }

  String value(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      return result.getString(1);
    }
  }
}
