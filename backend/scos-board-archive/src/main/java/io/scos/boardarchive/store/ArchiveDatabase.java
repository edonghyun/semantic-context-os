package io.scos.boardarchive.store;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** Owns the connection, SQL exceptions and atomic writes. No JDBC leaks into callers. */
final class ArchiveDatabase implements AutoCloseable {
  @FunctionalInterface
  interface Row<T> {
    T read(ResultSet rs) throws SQLException;
  }

  private final Connection connection;

  ArchiveDatabase(Path file) {
    try {
      connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      execute("PRAGMA busy_timeout=5000");
      execute("PRAGMA journal_mode=WAL");
      execute("PRAGMA foreign_keys=ON");
      migrate();
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private void migrate() {
    List<String> migrations =
        List.of(
            "V1__init.sql",
            "V2__sweep_discard_reason.sql",
            "V3__terminal_reappearance.sql",
            "V4__run_failure.sql",
            "V5__gone_evidence.sql",
            "V6__gone_since.sql",
            "V7__run_finished_at.sql");
    int version = query("PRAGMA user_version", rs -> rs.getInt(1)).getFirst();
    if (version > migrations.size())
      throw new IllegalStateException("Archive schema is newer than supported");
    for (int next = version + 1; next <= migrations.size(); next++)
      migrate(next, migrations.get(next - 1));
  }

  private void migrate(int version, String resource) {
    transaction(
        () -> {
          try (var in =
              ArchiveDatabase.class.getResourceAsStream("/db/board-archive/" + resource)) {
            if (in == null) throw new IllegalStateException("Missing archive schema");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : sql.split(";", -1))
              if (!statement.isBlank()) execute(statement);
            execute("PRAGMA user_version=" + version);
            return true;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  <T> List<T> query(String sql, Row<T> row, Object... args) {
    try (var st = connection.prepareStatement(sql)) {
      bind(st, args);
      try (var rs = st.executeQuery()) {
        List<T> rows = new ArrayList<>();
        while (rs.next()) rows.add(row.read(rs));
        return List.copyOf(rows);
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  int execute(String sql, Object... args) {
    try (var st = connection.prepareStatement(sql)) {
      bind(st, args);
      st.execute();
      return st.getUpdateCount();
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  long insertedId() {
    return query("SELECT last_insert_rowid()", rs -> rs.getLong(1)).getFirst();
  }

  <T> T transaction(Supplier<T> action) {
    try {
      connection.setAutoCommit(false);
      try {
        T value = action.get();
        connection.commit();
        return value;
      } catch (RuntimeException | Error e) {
        rollback(e);
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private void rollback(Throwable cause) {
    try {
      connection.rollback();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  private static void bind(PreparedStatement st, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      st.setObject(i + 1, args[i] instanceof Optional<?> o ? o.orElse(null) : args[i]);
  }

  private static IllegalStateException failure(SQLException e) {
    return new IllegalStateException("Archive storage failed", e);
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw failure(e);
    }
  }
}
