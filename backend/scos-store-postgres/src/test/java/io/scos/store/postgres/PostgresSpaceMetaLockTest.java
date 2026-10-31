package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 쓰기 트랜잭션의 세대 확인(stage2 §1.5.4): 투영이 트랜잭션 안에서 getLocked로 읽은 세대는 그 트랜잭션이 끝날 때까지 재임베딩이 pending으로 바꾸지
 * 못한다 — 확인과 쓰기 사이에 세대가 바뀌지 않는다. 다른 연결은 짧은 lock_timeout으로 막힘을 관찰한다(sleep 없음).
 */
@Tag("db")
@DisplayName("PostgreSQL getLocked는 트랜잭션 끝까지 그 키를 잠근다(stage2 §1.5.4)")
class PostgresSpaceMetaLockTest {
  static final SpaceId SPACE = new SpaceId("legacy");
  static final String KEY = "embedding:version";
  static final String GENERATION = "gemini:gemini-embedding-001:768:title+aliases+content";

  /** 다른 연결이 잠금을 기다리는 상한 — 넘으면 55P03(lock_not_available). */
  static final Duration OTHER_WRITER_LOCK_TIMEOUT = Duration.ofMillis(200);

  static final String LOCK_NOT_AVAILABLE = "55P03";

  /** 다른 연결에서 같은 키를 pending으로 바꿔 본다 — 막히면 그 SQLSTATE, 바뀌면 비어 있다. */
  static Optional<String> markPendingFromOtherConnection(DataSource ds) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET lock_timeout = '" + OTHER_WRITER_LOCK_TIMEOUT.toMillis() + "ms'");
      st.executeUpdate(
          "UPDATE space_meta SET value = 'pending:' || value WHERE space_id = 'legacy' AND key = 'embedding:version'");
      return Optional.empty();
    } catch (SQLException e) {
      return Optional.of(e.getSQLState());
    }
  }

  @Test
  @DisplayName("트랜잭션 안에서 getLocked한 키는 그 트랜잭션이 끝날 때까지 다른 연결이 바꾸지 못한다")
  void lockedKeyBlocksOtherWriters() {
    // given
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    SpaceMeta meta = PostgresTestAdapters.spaceMeta(ds);
    meta.set(SPACE, KEY, GENERATION);

    // when
    Optional<String> blocked =
        new TransactionTemplate(new DataSourceTransactionManager(ds))
            .execute(
                s -> {
                  var unused = meta.getLocked(SPACE, KEY);
                  return markPendingFromOtherConnection(ds);
                });

    // then
    assertThat(blocked).as("다른 연결의 UPDATE가 받은 SQLSTATE").contains(LOCK_NOT_AVAILABLE);
  }
}
