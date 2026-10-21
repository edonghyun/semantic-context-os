package io.scos.collection.internal.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** 다른 세션이 행 잠금을 쥔 상태(미커밋 SELECT … FOR UPDATE, 한 행 이상). 닫으면 롤백하고 연결을 반환한다. */
final class RowLock implements AutoCloseable {
  private final Connection connection;

  private RowLock(Connection connection) {
    this.connection = connection;
  }

  /** 행을 하나 이상 잠근다. 0행이면 아무것도 잠그지 않은 채 성공한 것이므로 롤백·반환하고 given 준비 실패로 멈춘다. */
  static RowLock hold(DataSource ds, String selectForUpdate) throws SQLException {
    Connection c = ds.getConnection();
    c.setAutoCommit(false);
    int locked = 0;
    try (Statement st = c.createStatement();
        ResultSet rows = st.executeQuery(selectForUpdate)) {
      while (rows.next()) locked++;
    } catch (SQLException | RuntimeException e) {
      new RowLock(c).close();
      throw e;
    }
    if (locked == 0) {
      new RowLock(c).close();
      throw new IllegalStateException("given 준비 실패: 잠근 행이 없다 — " + selectForUpdate);
    }
    return new RowLock(c);
  }

  @Override
  public void close() throws SQLException {
    try {
      connection.rollback();
    } finally {
      connection.close();
    }
  }
}
