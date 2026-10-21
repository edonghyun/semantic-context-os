package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.LeaseHeartbeat;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.Eventually;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestMetrics;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 운영 결함(T15.5 heartbeat): 문서·페이지 쓰기 트랜잭션의 `requireActive`가 점유 행을 FOR UPDATE로 커밋까지 쥐면, 다른 연결의
 * heartbeat `UPDATE work_claims`는 lock_timeout 뒤 55P03으로 끝난다. 이 실패는 일시적이다 — 점유를 잃은 것이 아니므로 다음 tick이
 * 다시 갱신하고, 안전성은 저장 경계가 지킨다(불변식 5). renew는 연결 lock_timeout(테스트 200ms)보다 긴 TTL/4까지 기다려, 긴 쓰기 트랜잭션이
 * 커밋되면 대기열 다음 차례로 잠금을 얻는다(T15.5 heartbeat 2차). TTL을 작게 잡아 대기 상한이 1초 안팎이다.
 */
@Tag("db")
@Timeout(60)
@DisplayName("점유 행 잠금을 얻지 못한 heartbeat는 다음 tick이 다시 갱신한다(lock_timeout)")
class HeartbeatLockContentionTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");

  /** renew 대기 상한은 TTL/4 = 500ms — 연결 lock_timeout(200ms)보다 길다. */
  static final Duration TTL = Duration.ofSeconds(2);

  static final Duration RENEW_LOCK_WAIT = Duration.ofMillis(500);
  static final Duration WITHIN_TTL = Duration.ofMillis(500);
  static final String LOCK_TIMEOUT = "200ms";

  /** 대기 상한 TTL/4 = 2s로 renew하는 TTL — 잠금이 연결 lock_timeout보다 오래 잡혀도 상한 전에 풀린다. */
  static final Duration LONG_WAIT_TTL = Duration.ofSeconds(8);

  /** 연결 lock_timeout(200ms)의 두 배 — 이만큼 기다린 renew는 연결 설정보다 오래 기다린 것이다. */
  static final Duration PAST_CONNECTION_TIMEOUT = Duration.ofMillis(400);

  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /** {@link #LOCK_TIMEOUT}과 같은 값. */
  static final Duration CONNECTION_LOCK_TIMEOUT = Duration.ofMillis(200);

  /** 대기 TTL/4 = 100ms가 연결 lock_timeout(200ms)보다 짧은 TTL. */
  static final Duration SHORT_WAIT_TTL = Duration.ofMillis(400);

  static final String LOCK_CLAIM_ROW =
      "SELECT * FROM work_claims WHERE source_instance_id = '" + SOURCE.value() + "' FOR UPDATE";

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  final WorkClaimPort claims =
      PostgresCollectionConfiguration.adapters(ResourceLimitTest.withLockTimeout(LOCK_TIMEOUT))
          .claims();
  WorkClaim claim;

  @BeforeEach
  void givenClaimed() {
    PostgresSupport.truncateAll();
    claim =
        claims
            .tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 빈 소스를 점유하지 못했다"));
    clock.advance(WITHIN_TTL);
  }

  Instant storedExpiry() {
    return claims.current(SOURCE).orElseThrow().expiresAt();
  }

  /** renew가 끝났거나, 점유 행 잠금을 waited보다 오래 기다리는 중일 때까지(PostgreSQL pg_stat_activity로 관찰). */
  static void awaitRenewDoneOrWaitingLongerThan(CompletableFuture<?> renew, Duration waited) {
    JdbcClient observer = JdbcClient.create(PostgresSupport.dataSource());
    Eventually.await(
        "renew가 끝나거나 " + waited.toMillis() + "ms 넘게 잠금을 기다림",
        () ->
            renew.isDone()
                || observer
                        .sql(
                            """
                            SELECT count(*) FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query LIKE '%UPDATE work_claims%'
                              AND clock_timestamp() - query_start > make_interval(secs => :secs)
                            """)
                        .param("secs", waited.toMillis() / 1000.0)
                        .query(Long.class)
                        .single()
                    > 0,
        WAIT_LIMIT);
  }

  @Nested
  @DisplayName("다른 트랜잭션이 점유 행을 대기 상한(TTL/4)보다 오래 쥐면")
  class LockHeldPastRenewWait {
    Throwable failure;
    Duration waited;

    @BeforeEach
    void givenRenewWhileLocked() throws Exception {
      try (RowLock held = RowLock.hold(PostgresSupport.dataSource(), LOCK_CLAIM_ROW)) {
        long started = System.nanoTime();
        failure = catchThrowable(() -> claims.renew(claim, TTL, clock.instant()));
        waited = Duration.ofNanos(System.nanoTime() - started);
      }
    }

    @Test
    @DisplayName("renew는 CannotAcquireLockException(55P03)으로 끝난다")
    void contendedRenewIsCannotAcquireLock() {
      // then: 기대 종류는 손으로 쓴다
      assertThat(failure).isExactlyInstanceOf(CannotAcquireLockException.class);
      assertThat(
              ((SQLException) ((CannotAcquireLockException) failure).getRootCause()).getSQLState())
          .isEqualTo("55P03");
    }

    @Test
    @DisplayName("renew는 연결 lock_timeout이 아니라 대기 상한까지 기다린다")
    void waitsUpToRenewLockWait() {
      // then
      assertThat(waited).isGreaterThanOrEqualTo(RENEW_LOCK_WAIT);
    }
  }

  @Test
  @DisplayName("TTL/4가 연결 lock_timeout보다 짧으면 renew는 연결 설정만큼 기다린다")
  void neverWaitsLessThanConnectionTimeout() throws Exception {
    // given: TTL/4 = 100ms < 연결 lock_timeout 200ms
    Duration waited;
    Throwable failure;
    try (RowLock held = RowLock.hold(PostgresSupport.dataSource(), LOCK_CLAIM_ROW)) {
      long started = System.nanoTime();

      // when
      failure = catchThrowable(() -> claims.renew(claim, SHORT_WAIT_TTL, clock.instant()));
      waited = Duration.ofNanos(System.nanoTime() - started);
    }

    // then
    assertThat(failure).isExactlyInstanceOf(CannotAcquireLockException.class);
    assertThat(waited).isGreaterThanOrEqualTo(CONNECTION_LOCK_TIMEOUT);
  }

  @Nested
  @DisplayName("다른 트랜잭션이 점유 행을 연결 lock_timeout보다 길게, 대기 상한보다 짧게 쥐었다 커밋하면")
  class LockReleasedWithinRenewWait {
    Throwable failure;

    @BeforeEach
    void givenLockReleasedWhileRenewWaits() throws Exception {
      CompletableFuture<WorkClaim> renew;
      try (RowLock held = RowLock.hold(PostgresSupport.dataSource(), LOCK_CLAIM_ROW)) {
        renew =
            CompletableFuture.supplyAsync(
                () -> claims.renew(claim, LONG_WAIT_TTL, clock.instant()));
        awaitRenewDoneOrWaitingLongerThan(renew, PAST_CONNECTION_TIMEOUT);
      }
      failure = catchThrowable(() -> renew.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("renew가 잠금을 얻어 성공한다")
    void renewSucceeds() {
      // then
      assertThat(failure).isNull();
    }

    @Test
    @DisplayName("저장된 점유가 현재 시각 + TTL로 연장된다")
    void extendsStoredClaim() {
      // then
      assertThat(storedExpiry()).isEqualTo(clock.instant().plus(LONG_WAIT_TTL));
    }
  }

  @Nested
  @DisplayName("다른 트랜잭션이 점유 행을 쥔 동안 heartbeat가 tick하면")
  class TickWhileClaimRowLocked {
    LeaseHeartbeat heartbeat;

    @BeforeEach
    void givenContendedTick() throws Exception {
      CollectionMetrics metrics = TestMetrics.collection();
      heartbeat = new LeaseHeartbeats(claims, TTL, clock, scheduler, metrics).begin(claim);
      heartbeat.start();
      try (RowLock held = RowLock.hold(PostgresSupport.dataSource(), LOCK_CLAIM_ROW)) {
        scheduler.tick();
      }
      if (!storedExpiry().equals(claim.expiresAt()))
        throw new IllegalStateException("given 준비 실패: 잠긴 동안 renew가 점유를 바꿨다");
    }

    @Test
    @DisplayName("assertHealthy는 통과한다")
    void staysHealthy() {
      // when·then
      assertThatCode(heartbeat::assertHealthy).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("잠금이 풀린 뒤 다음 tick이 점유를 현재 시각 + TTL로 연장한다")
    void nextTickExtendsClaim() {
      // given
      clock.advance(WITHIN_TTL);

      // when
      scheduler.tick();

      // then
      assertThat(storedExpiry()).isEqualTo(clock.instant().plus(TTL));
    }
  }
}
