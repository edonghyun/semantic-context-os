package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 실제 읽기 전용 연결로 토큰 읽기·쓰기 경계를 관찰한다. */
@Tag("db")
final class UserTokenReadSource extends DriverManagerDataSource {
  private int connections;

  UserTokenReadSource() {
    super(PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password());
    var properties = new Properties();
    properties.setProperty("options", "-c default_transaction_read_only=on");
    setConnectionProperties(properties);
  }

  @Override
  public Connection getConnection() throws SQLException {
    connections++;
    return super.getConnection();
  }

  int openedConnections() {
    return connections;
  }
}
