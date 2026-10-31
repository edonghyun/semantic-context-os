package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.server.McpHttpConfiguration.McpHttpProperties;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("db")
@DisplayName("데몬 HTTP MCP의 읽기 풀은 실제 PostgreSQL에서 쓰기를 거부한다")
class McpHttpReadOnlyPoolTest {
  static final String READ_ONLY_TRANSACTION = "25006";
  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
  static final String VALID_TOKEN = "r".repeat(McpHttpConfiguration.MIN_TOKEN_LENGTH);
  static final int SINGLE_CONNECTION = 1;

  @Test
  @DisplayName("읽기 풀 연결로 테이블을 만들면 read-only 트랜잭션 오류다")
  void refusesWrites() throws Exception {
    // given
    DataSourceProperties db = new DataSourceProperties();
    db.setUrl(PostgresSupport.jdbcUrl());
    db.setUsername(PostgresSupport.username());
    db.setPassword(PostgresSupport.password());
    McpHttpProperties http =
        new McpHttpProperties(
            true, VALID_TOKEN, SINGLE_CONNECTION, CONNECTION_TIMEOUT, List.of(), List.of());

    try (HikariDataSource read =
        McpHttpConfiguration.readOnlyPool(db, http, new SimpleMeterRegistry())) {
      // when
      Throwable refused =
          catchThrowable(
              () -> new JdbcTemplate(read).execute("CREATE TABLE mcp_read_probe (x int)"));

      // then
      assertThat(refused)
          .as("읽기 풀로 테이블을 만들 수 있으면 안 된다")
          .isInstanceOf(DataAccessException.class)
          .extracting(e -> ((DataAccessException) e).getMostSpecificCause())
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> assertThat(e.getSQLState()).isEqualTo(READ_ONLY_TRANSACTION));
    }
  }
}
