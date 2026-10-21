package io.scos.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;

/** 결정적 경합(TESTING §7): B의 백엔드가 A의 pid에 블록될 때까지 서버 측 조건을 기다린다. 상한은 hang 방지용. */
public final class PgLocks {
  private PgLocks() {}

  public static int backendPid(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** 어떤 백엔드든 blockerPid에 블록된 것이 생길 때까지 기다린다(pg_blocking_pids). */
  public static void awaitBlockedBy(DataSource ds, int blockerPid, Duration waitLimit)
      throws Exception {
    try (Connection c = ds.getConnection()) {
      ChildJvm.awaitCondition(
          "a backend blocked by pid " + blockerPid,
          () -> {
            try {
              return anyBlockedBy(c, blockerPid);
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          },
          waitLimit.toMillis());
    }
  }

  /** 지금 어떤 백엔드가 blockerPid에 블록돼 있는가(pg_blocking_pids). */
  public static boolean anyBlockedBy(DataSource ds, int blockerPid) {
    try (Connection c = ds.getConnection()) {
      return anyBlockedBy(c, blockerPid);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean anyBlockedBy(Connection c, int blockerPid) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE "
                    + blockerPid
                    + " = ANY(pg_blocking_pids(pid))")) {
      rs.next();
      return rs.getInt(1) > 0;
    }
  }
}
