package io.scos.collection.internal.postgres;

import static io.scos.collection.internal.postgres.CollectionScenario.HISTORY;
import static io.scos.collection.internal.postgres.CollectionScenario.SOURCE;
import static io.scos.collection.internal.postgres.CollectionScenario.TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import com.zaxxer.hikari.HikariDataSource;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunStatus;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 자원 상한 초과 시 동작(ADR-0020 §4): 잠금 대기는 lock_timeout 뒤 55P03 → run failed(storage_failed)·점유 해제; 연결 풀
 * 고갈은 connection-timeout 뒤 실패 — 점유 전이면 예외(run 없음), run 중이면 마감도 못 해 점유 TTL 뒤 다음 run이 stale_run으로
 * 정리한다. 무기한 대기는 없다 — 상한이 효력이 없으면 결과 대신 dbTest 시간 상한(hang 방지)에 걸린다. 상황마다 @BeforeAll에서 한 번 실행한다.
 */
@Tag("db")
@Timeout(120)
@DisplayName("자원 상한을 넘으면 무기한 기다리지 않고 저장소 장애로 끝난다(ADR-0020 §4)")
class ResourceLimitTest {
  static final String LOCK_TIMEOUT = "1s";
  static final int ONE_CONNECTION = 1;
  static final long CONNECTION_TIMEOUT_MS = 1_000;

  /** 앱 연결과 같은 lock_timeout을 가진 연결. */
  static DataSource withLockTimeout(String lockTimeout) {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password());
    Properties props = new Properties();
    props.setProperty("options", "-c lock_timeout=" + lockTimeout);
    ds.setConnectionProperties(props);
    return ds;
  }

  /** 상한을 가진 풀. */
  static HikariDataSource pool(int size, long connectionTimeoutMs) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(PostgresSupport.jdbcUrl());
    ds.setUsername(PostgresSupport.username());
    ds.setPassword(PostgresSupport.password());
    ds.setMaximumPoolSize(size);
    ds.setConnectionTimeout(connectionTimeoutMs);
    return ds;
  }

  abstract static class Situation {
    final CollectionScenario collection = new CollectionScenario();

    @AfterAll
    void close() {
      collection.close();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("다른 세션이 체크포인트 행을 쥔 채 페이지 커밋을 시도하면")
  class LockWait extends Situation {
    CollectionRuntime.Report report;

    @BeforeAll
    void collectWhileRowLocked() throws Exception {
      if (collection.collect("w1").status() != RunStatus.SUCCEEDED_WITH_REJECTIONS)
        throw new IllegalStateException("given 준비 실패: 첫 수집으로 체크포인트 행이 생겨야 한다");
      collection.slackMessageAdded("잠금 대기 중 들어온 메시지", "1760486440.000000");
      collection.advanceClock(Duration.ofSeconds(1)); // 두 run의 started_at이 구분되게(결과 순서가 결정적)
      try (RowLock held =
          RowLock.hold(
              PostgresSupport.dataSource(),
              "SELECT * FROM source_checkpoints WHERE source_instance_id = '"
                  + SOURCE.value()
                  + "' FOR UPDATE")) {
        report = collection.collectUsing(withLockTimeout(LOCK_TIMEOUT));
      }
    }

    @Test
    @DisplayName("lock_timeout 뒤 run 보고는 failed(storage_failed)다")
    void runFailsAsStorageFailure() {
      // then
      assertThat(report)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::errorKind)
          .containsExactly(RunStatus.FAILED, Optional.of("storage_failed"));
    }

    @Test
    @DisplayName("lock_timeout 뒤 저장된 run도 failed(storage_failed)로 마감된다")
    void storedRunFailsAsStorageFailure() {
      // then
      assertThat(collection.runStatuses())
          .containsExactly("succeeded_with_rejections/-", "failed/storage_failed");
    }

    @Test
    @DisplayName("점유는 해제된다")
    void releasesClaim() {
      // then
      assertThat(collection.hasActiveClaim()).isFalse();
    }

    @Test
    @DisplayName("그 페이지는 커밋되지 않는다")
    void pageIsNotCommitted() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("풀이 고갈된 채 수집을 시작하면")
  class ExhaustedBeforeClaim extends Situation {
    RuntimeException failure;
    boolean heldStillOpen;

    @BeforeAll
    void collectWithExhaustedPool() throws Exception {
      try (HikariDataSource pool = pool(ONE_CONNECTION, CONNECTION_TIMEOUT_MS);
          Connection held = pool.getConnection()) {
        failure = collection.collectionFailureUsing(pool);
        heldStillOpen = !held.isClosed();
      }
    }

    @Test
    @DisplayName("connection-timeout 뒤 저장소 장애로 끝난다")
    void failsAsStorageFailure() {
      // then: 기대 종류는 손으로 쓴다 — 분류기(CollectionRuntime.isStorageFailure)를 기대값으로 쓰지 않는다
      assertThat(failure)
          .isInstanceOf(CannotGetJdbcConnectionException.class)
          .hasRootCauseInstanceOf(SQLTransientConnectionException.class);
    }

    @Test
    @DisplayName("다른 쪽이 쥔 연결은 건드리지 않는다")
    void leavesHeldConnection() {
      // then
      assertThat(heldStillOpen).isTrue();
    }

    @Test
    @DisplayName("run이 남지 않는다")
    void leavesNothing() {
      // then
      assertThat(collection.latestRunId()).isEmpty();
    }

    @Test
    @DisplayName("점유가 남지 않는다")
    void leavesNoClaim() {
      // then
      assertThat(collection.hasClaim()).isFalse();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("run 도중 풀이 고갈되면")
  class ExhaustedMidRun extends Situation {
    Throwable failure;
    List<String> statusesAfterFailure;
    boolean claimLeft;

    @BeforeAll
    void exhaustMidRun() throws Exception {
      collection.holdRequest(HISTORY);
      try (HikariDataSource pool = pool(ONE_CONNECTION, CONNECTION_TIMEOUT_MS)) {
        Future<CollectionRuntime.Report> run = collection.collectInBackgroundUsing(pool);
        collection.awaitRequestHeld(); // 점유 뒤 공급자 호출 중
        try (Connection held = pool.getConnection()) {
          collection.releaseRequest();
          failure = CollectionScenario.failureOf(run);
        }
      }
      statusesAfterFailure = collection.runStatuses();
      claimLeft = collection.hasClaim();
    }

    @Test
    @DisplayName("페이지 커밋·마감이 연결을 얻지 못해 저장소 장애로 끝난다")
    void failsAsStorageFailure() {
      // then: 기대 종류는 손으로 쓴다 — 분류기(CollectionRuntime.isStorageFailure)를 기대값으로 쓰지 않는다
      assertThat(failure)
          .isInstanceOf(CannotGetJdbcConnectionException.class)
          .hasRootCauseInstanceOf(SQLTransientConnectionException.class);
    }

    @Test
    @DisplayName("run은 running으로 남는다")
    void leavesRunningRun() {
      // then
      assertThat(statusesAfterFailure).containsExactly("running/-");
    }

    @Test
    @DisplayName("점유는 그대로 남는다")
    void leavesClaim() {
      // then
      assertThat(claimLeft).isTrue();
    }

    @Nested
    @TestInstance(PER_CLASS)
    @DisplayName("점유 TTL이 지나 다시 수집하면")
    class CollectAgainAfterTtl {
      CollectionRuntime.Report next;

      @BeforeAll
      void collectAgain() {
        collection.advanceClock(TTL.plusSeconds(1));
        collection.stopHoldingRequests();
        next = collection.collect("w1");
      }

      @Test
      @DisplayName("다음 run이 남은 run을 stale_run으로 마감한다")
      void nextRunCleansUp() {
        // then
        assertThat(collection.runStatuses())
            .containsExactly("failed/stale_run", "succeeded_with_rejections/-");
      }

      @Test
      @DisplayName("다음 run은 수집을 끝낸다")
      void nextRunCollects() {
        // then
        assertThat(next.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
      }

      @Test
      @DisplayName("다음 run이 원본 9건을 넣는다")
      void nextRunStoresItems() {
        // then
        assertThat(collection.rawItems()).isEqualTo(9);
      }
    }
  }
}
