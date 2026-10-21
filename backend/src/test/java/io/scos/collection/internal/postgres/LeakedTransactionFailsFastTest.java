package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 테스트 격리 장치 자체의 성질: 어떤 테스트가 트랜잭션(행 잠금)을 누수해도 다음 테스트의 TRUNCATE는 lock_timeout으로 **실패**하지 스위트를 멈추지
 * 않는다(TESTING §7 — 상한은 hang 방지용, 실패는 실패로). 잠금을 쥔 채 정리를 한 번 시도하고(@BeforeAll) 기록을 읽는다.
 */
@Tag("db")
@Timeout(90)
@DisplayName("테스트 정리는 누수된 행 잠금에 멈추지 않고 실패한다(TESTING §7)")
class LeakedTransactionFailsFastTest {
  /** lock_timeout이 효력이 없을 때만 넘는 상한. */
  static final Duration WELL_BEFORE_HANG = Duration.ofSeconds(60);

  @Test
  @DisplayName("테스트 연결의 lock_timeout은 PostgresSupport가 정한 값이다")
  void lockTimeoutIsConfigured() {
    // when
    String lockTimeout =
        JdbcClient.create(PostgresSupport.dataSource())
            .sql("SHOW lock_timeout")
            .query(String.class)
            .single();

    // then
    assertThat(lockTimeout).isEqualTo(PostgresSupport.LOCK_TIMEOUT);
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("다른 세션이 행 잠금을 쥔 채 멈춰 있을 때 truncateAll하면")
  class TruncateWhileLocked {
    RuntimeException failure;
    Duration took;

    @BeforeAll
    void truncateWhileLocked() throws Exception {
      PostgresSupport.truncateAll();
      JdbcClient.create(PostgresSupport.dataSource())
          .sql(
              "INSERT INTO work_claims (source_instance_id, owner_id, fencing_token, expires_at) VALUES ('leak', 'x', 1, now())")
          .update();
      try (RowLock leaked =
          RowLock.hold(
              PostgresSupport.dataSource(),
              "SELECT * FROM work_claims WHERE source_instance_id = 'leak' FOR UPDATE")) {
        long started = System.nanoTime();
        try {
          PostgresSupport.truncateAll();
        } catch (RuntimeException e) {
          failure = e;
        }
        took = Duration.ofNanos(System.nanoTime() - started);
      }
    }

    @Test
    @DisplayName("lock timeout을 밝힌 예외로 끝난다")
    void failsWithLockTimeout() {
      // then
      assertThat(failure)
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("lock timeout");
    }

    @Test
    @DisplayName("hang 상한보다 훨씬 일찍 돌아온다")
    void returnsPromptly() {
      // then
      assertThat(took).isLessThan(WELL_BEFORE_HANG);
    }
  }
}
