package io.scos.store.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.sqlite.SQLiteDataSource;
import org.sqlite.jdbc4.JDBC4Connection;
import org.sqlite.jdbc4.JDBC4Statement;

/** 실제 SQLite 드라이버의 WAL 전환 한 번만 SQLITE_BUSY로 실패시킨다. SQL 실행은 드라이버가 맡는다. */
final class WalContentionDataSource extends SQLiteDataSource {
  private boolean failNextWal;
  private int walFailures;

  void failNextWal() {
    failNextWal = true;
  }

  int walFailures() {
    return walFailures;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return new ContendedConnection(getUrl(), getConfig().toProperties());
  }

  private final class ContendedConnection extends JDBC4Connection {
    ContendedConnection(String url, Properties properties) throws SQLException {
      super(url, url.substring("jdbc:sqlite:".length()), properties);
    }

    @Override
    public Statement createStatement() {
      return new JDBC4Statement(this) {
        @Override
        public boolean execute(String sql) throws SQLException {
          if (sql.equals("PRAGMA journal_mode = WAL") && failNextWal) {
            failNextWal = false;
            walFailures++;
            throw new SQLException("synthetic WAL contention", "SQLITE_BUSY", 5);
          }
          return super.execute(sql);
        }
      };
    }
  }
}
