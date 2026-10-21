package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 행 잠금 어휘 자체 테스트(F15 C7): 잠금 대기·누수 트랜잭션 테스트가 기대는 "다른 세션이 이 행을 쥐고 있다"가 실제로 성립하는지, 잠글 행이 없을 때 조용히
 * 성공하지 않는지 본다.
 */
@DisplayName("행 잠금 어휘는 다른 세션을 실제로 막고 빈 잠금은 멈춘다(C7)")
@Tag("db")
class RowLockTest {
  static final String LOCK_ROW =
      "SELECT * FROM work_claims WHERE source_instance_id = 'row-lock' FOR UPDATE";

  /** 기다리지 않고 잠근다 — 이미 다른 세션이 쥐었으면 55P03(lock_not_available)으로 실패한다. */
  static final String TRY_LOCK_NOWAIT =
      "SELECT * FROM work_claims WHERE source_instance_id = 'row-lock' FOR UPDATE NOWAIT";

  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  /** 다른 세션에서 NOWAIT로 잠가 보고 던진 예외를 돌려준다(잠기면 null). */
  static Throwable tryLockFromAnotherSession() {
    return catchThrowable(
        () -> {
          try (Connection c = PostgresSupport.dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
              st.executeQuery(TRY_LOCK_NOWAIT).close();
            } finally {
              c.rollback();
            }
          }
        });
  }

  @Test
  @DisplayName("잠글 행이 없으면 given 준비 실패로 멈춘다")
  void stopsWhenNoRowLocked() {
    // when: 잘못 성공하더라도 잡은 연결은 닫는다(다음 테스트의 TRUNCATE가 막히지 않게)
    Throwable thrown =
        catchThrowable(
            () -> {
              try (RowLock unused = RowLock.hold(PostgresSupport.dataSource(), LOCK_ROW)) {
                // 잠근 행이 없으면 여기까지 오지 않는다
              }
            });

    // then
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("given 준비 실패: 잠근 행이 없다");
  }

  @Nested
  @DisplayName("행이 있으면")
  class WithRow {
    @BeforeEach
    void givenRow() {
      JdbcClient.create(PostgresSupport.dataSource())
          .sql(
              "INSERT INTO work_claims (source_instance_id, owner_id, fencing_token, expires_at) VALUES ('row-lock', 'x', 1, now())")
          .update();
    }

    @Test
    @DisplayName("쥐고 있는 동안 다른 세션은 그 행을 잠그지 못한다")
    void otherSessionCannotLock() throws SQLException {
      // when
      Throwable other;
      try (RowLock held = RowLock.hold(PostgresSupport.dataSource(), LOCK_ROW)) {
        other = tryLockFromAnotherSession();
      }

      // then
      assertThat(other)
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("55P03");
    }

    @Test
    @DisplayName("닫으면 다른 세션이 잠글 수 있다")
    void releasedOnClose() throws SQLException {
      // given
      RowLock.hold(PostgresSupport.dataSource(), LOCK_ROW).close();

      // when
      Throwable other = tryLockFromAnotherSession();

      // then
      assertThat(other).isNull();
    }
  }
}
