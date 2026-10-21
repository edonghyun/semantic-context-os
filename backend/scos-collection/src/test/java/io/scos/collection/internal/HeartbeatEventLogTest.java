package io.scos.collection.internal;

import static io.scos.collection.internal.HeartbeatScenario.SOURCE;
import static io.scos.collection.internal.HeartbeatScenario.TTL;
import static io.scos.collection.internal.HeartbeatScenario.WITHIN_TTL;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.dao.CannotAcquireLockException;
import tools.jackson.databind.JsonNode;

/**
 * 점유 갱신의 운영 이벤트 로그(TS collection-runtime.ts LeaseHeartbeat — 데몬 JSON 로그): renew가 주기(TTL/2)의 절반보다 오래
 * 걸리면 warn `lease.heartbeat.slow`(detail.elapsedMs), renew가 실패하면 warn
 * `lease.heartbeat.failed`(detail.message). 점유 행의 잠금을 얻지 못한 renew는 실패가 아니라 warn
 * `lease.heartbeat.contended`(Java·PostgreSQL 전용 — TS SQLite에는 행 잠금 경합이 없다).
 */
@DisplayName("점유 갱신이 느리거나 실패하면 warn 이벤트를 남긴다(TS LeaseHeartbeat)")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class HeartbeatEventLogTest {
  /** 주기(TTL/2 = 15s)의 절반 — 이것을 넘어야 느리다(TS `elapsedMs > intervalMs / 2`). */
  static final Duration HALF_INTERVAL = TTL.dividedBy(4);

  /** 주기(TTL/2 = 15s)의 절반(7.5s)을 넘는 renew 시간. */
  static final Duration SLOW_RENEW = Duration.ofSeconds(8);

  final HeartbeatScenario scenario = new HeartbeatScenario();
  final LogCapture log = LogCapture.of(LeaseHeartbeat.class);

  @AfterEach
  void tearDown() {
    log.close();
    scenario.close();
  }

  @Nested
  @DisplayName("renew가 진행 중인 tick에서")
  class RenewInProgress {
    CompletableFuture<Void> tick;

    @BeforeEach
    void givenRenewInProgress() {
      scenario.renewsHeld().start();
      tick = scenario.tickWithRenewInProgress();
    }

    @Test
    @DisplayName("renew가 주기의 절반보다 오래 걸리면 warn lease.heartbeat.slow에 걸린 시간을 남긴다")
    void logsSlowRenew() throws Exception {
      // when
      scenario.advanceClock(SLOW_RENEW);
      scenario.releaseRenew();
      HeartbeatScenario.await(tick);

      // then
      JsonNode line = log.events("lease.heartbeat.slow").getFirst();
      assertThat(line.get("sourceInstanceId").asString()).isEqualTo(SOURCE.value());
      assertThat(line.get("detail").get("elapsedMs").asLong()).isEqualTo(SLOW_RENEW.toMillis());
    }

    @Test
    @DisplayName("주기의 절반과 같은 시간이 걸린 renew는 남기지 않는다")
    void quietAtHalfInterval() throws Exception {
      // when
      scenario.advanceClock(HALF_INTERVAL);
      scenario.releaseRenew();
      HeartbeatScenario.await(tick);

      // then
      assertThat(log.events()).isEmpty();
    }
  }

  @Test
  @DisplayName("renew가 실패하면 warn lease.heartbeat.failed를 소스·메시지와 함께 남긴다")
  void logsFailedRenew() {
    // given: 점유가 만료된 뒤
    scenario.start();
    scenario.advanceClock(TTL.plusSeconds(1));

    // when
    scenario.tick();

    // then
    JsonNode line = log.events("lease.heartbeat.failed").getFirst();
    assertThat(line.get("level").asString()).isEqualTo("warn");
    assertThat(line.get("detail").get("message").asString()).contains(SOURCE.value());
  }

  @Nested
  @DisplayName("renew가 점유 행의 잠금을 얻지 못하면(lock_timeout)")
  class RenewLockContended {
    @BeforeEach
    void givenContendedRenew() {
      scenario
          .nextRenewFails(new CannotAcquireLockException("canceling statement due to lock timeout"))
          .start();
      scenario.advanceClock(WITHIN_TTL);
      scenario.tick();
    }

    @Test
    @DisplayName("warn lease.heartbeat.contended를 소스·메시지와 함께 남긴다")
    void logsContendedRenew() {
      // then
      JsonNode line = log.events("lease.heartbeat.contended").getFirst();
      assertThat(line.get("level").asString()).isEqualTo("warn");
      assertThat(line.get("sourceInstanceId").asString()).isEqualTo(SOURCE.value());
      assertThat(line.get("detail").get("message").asString())
          .isEqualTo("canceling statement due to lock timeout");
    }

    @Test
    @DisplayName("lease.heartbeat.failed는 남기지 않는다")
    void doesNotLogFailure() {
      // then
      assertThat(log.events("lease.heartbeat.failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("메시지 없는 실패는 예외 문자열로 남긴다(TS String(error))")
  void messageFallsBackToExceptionString() {
    // when
    String message = LeaseHeartbeat.failureMessage(new IllegalStateException());

    // then
    assertThat(message).isEqualTo("java.lang.IllegalStateException");
  }
}
