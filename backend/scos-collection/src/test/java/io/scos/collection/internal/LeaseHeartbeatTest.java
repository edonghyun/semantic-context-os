package io.scos.collection.internal;

import static io.scos.collection.internal.HeartbeatScenario.SOURCE;
import static io.scos.collection.internal.HeartbeatScenario.TTL;
import static io.scos.collection.internal.HeartbeatScenario.WITHIN_TTL;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.support.BackgroundThread;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * F3: heartbeat는 TTL/2마다 점유를 연장하고, 실패는 assertHealthy에서 드러나며, stop은 진행 중 renew를 기다리고 타이머를 끈다. 행 잠금을
 * 얻지 못한 renew(PostgreSQL lock_timeout — 같은 점유 행을 FOR UPDATE로 쥔 쓰기 트랜잭션)만은 일시적이라 다음 tick이 다시 시도한다:
 * 안전성은 저장 경계가 지키고(불변식 5), TTL 안의 한 번 놓친 갱신은 무해하다. TS SQLite에는 행 잠금 경합이 없다.
 */
@DisplayName("점유 heartbeat는 TTL/2마다 점유를 연장하고, stop하면 끈다(F3)")
class LeaseHeartbeatTest {
  static final Duration HALF_TTL = TTL.dividedBy(2);

  /** stop 스레드가 돌아오기를 기다리는 상한(프로세스 안 대기, hang 방지). */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  final HeartbeatScenario scenario = new HeartbeatScenario();

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  @Nested
  @DisplayName("시작해 TTL 안에서 tick하면")
  class TickWithinTtl {
    LeaseHeartbeat heartbeat;

    @BeforeEach
    void givenStartedWithinTtl() {
      heartbeat = scenario.start();
      scenario.advanceClock(WITHIN_TTL);
    }

    @Test
    @DisplayName("작업 하나를 등록한다")
    void registersOneTask() {
      // when
      scenario.tick();

      // then
      assertThat(scenario.registeredTasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("작업 주기는 TTL/2다")
    void registersHalfTtlTask() {
      // when
      scenario.tick();

      // then
      assertThat(scenario.registeredPeriod()).isEqualTo(HALF_TTL);
    }

    @Test
    @DisplayName("저장된 점유 만료가 현재 시각 + TTL로 연장된다")
    void extendsStoredClaim() {
      // when
      scenario.tick();

      // then
      assertThat(scenario.storedExpiry()).isEqualTo(scenario.now().plus(TTL));
    }

    @Test
    @DisplayName("heartbeat가 들고 있는 점유도 연장된 값이다")
    void heldClaimIsExtended() {
      // when
      scenario.tick();

      // then
      assertThat(heartbeat.claim().expiresAt()).isEqualTo(scenario.now().plus(TTL));
    }

    @Test
    @DisplayName("assertHealthy는 통과한다")
    void isHealthy() {
      // when
      scenario.tick();

      // then
      assertThatCode(heartbeat::assertHealthy).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("점유가 만료된 뒤 renew하면")
  class RenewAfterExpiry {
    LeaseHeartbeat heartbeat;

    @BeforeEach
    void givenStartedThenExpired() {
      heartbeat = scenario.start();
      scenario.advanceClock(TTL.plusSeconds(1));
    }

    @Test
    @DisplayName("assertHealthy가 소스를 밝힌 ClaimLostException을 던진다")
    void surfacesFailure() {
      // when
      scenario.tick();

      // then
      assertThatThrownBy(heartbeat::assertHealthy)
          .isInstanceOf(ClaimLostException.class)
          .hasMessageContaining(SOURCE.value());
    }

    @Test
    @DisplayName("타이머가 꺼져 다음 tick은 점유 저장소를 다시 부르지 않는다")
    void stopsRenewing() {
      // given: 만료 뒤 첫 tick의 renew가 실패했다
      scenario.tick();
      int renewsAfterFailure = scenario.renewCalls();

      // when
      scenario.tick();

      // then
      assertThat(scenario.renewCalls()).isEqualTo(renewsAfterFailure);
    }

    @Test
    @DisplayName("스케줄러 슬롯을 반환한다")
    void releasesSchedulerSlot() {
      // when
      scenario.tick();

      // then
      assertThat(scenario.liveTasks()).isZero();
    }
  }

  @Nested
  @DisplayName("renew가 점유 행의 잠금을 얻지 못하면(lock_timeout)")
  class RenewLockContended {
    LeaseHeartbeat heartbeat;

    @BeforeEach
    void givenContendedRenew() {
      heartbeat =
          scenario
              .nextRenewFails(
                  new CannotAcquireLockException("canceling statement due to lock timeout"))
              .start();
      scenario.advanceClock(WITHIN_TTL);
      scenario.tick();
    }

    @Test
    @DisplayName("assertHealthy는 통과한다")
    void staysHealthy() {
      // when·then
      assertThatCode(heartbeat::assertHealthy).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("타이머를 유지한다")
    void keepsTimer() {
      // then
      assertThat(scenario.liveTasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 tick이 점유를 현재 시각 + TTL로 연장한다")
    void nextTickExtendsClaim() {
      // given
      scenario.advanceClock(WITHIN_TTL);

      // when
      scenario.tick();

      // then
      assertThat(scenario.storedExpiry()).isEqualTo(scenario.now().plus(TTL));
    }

    @Test
    @DisplayName("heartbeat 지표에 outcome=failed로 센다")
    void countsAsFailed() {
      // then
      assertThatMetrics(scenario.registry())
          .hasCounter(CollectionMetrics.LEASE_HEARTBEAT, "outcome", "failed", 1);
    }
  }

  @Nested
  @DisplayName("renew가 잠금 경합이 아닌 저장소 장애로 실패하면")
  class RenewStorageFailure {
    final DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("connection reset");
    LeaseHeartbeat heartbeat;

    @BeforeEach
    void givenFailedRenew() {
      heartbeat = scenario.nextRenewFails(failure).start();
      scenario.advanceClock(WITHIN_TTL);
      scenario.tick();
    }

    @Test
    @DisplayName("assertHealthy가 그 실패를 던진다")
    void surfacesFailure() {
      // when·then
      assertThatThrownBy(heartbeat::assertHealthy).isSameAs(failure);
    }

    @Test
    @DisplayName("타이머가 꺼져 스케줄러 슬롯을 반환한다")
    void stopsTimer() {
      // then
      assertThat(scenario.liveTasks()).isZero();
    }
  }

  @Nested
  @DisplayName("renew가 진행 중일 때 stop하면")
  class StopDuringRenew {
    CompletableFuture<Void> tick;

    @BeforeEach
    void givenRenewInProgress() {
      scenario.renewsHeld().start();
      tick = scenario.tickWithRenewInProgress();
    }

    /** stop을 부르고 renew를 풀어 stop이 돌아오고 tick이 끝나기까지. 돌아오지 않으면 given 준비 실패로 멈춘다. */
    void givenStoppedAfterRenew() throws Exception {
      BackgroundThread stopping = scenario.stopInBackground();
      scenario.releaseRenew();
      if (!stopping.returnsWithin(WAIT_LIMIT)) {
        throw new IllegalStateException("given 준비 실패: renew가 끝났는데 stop이 돌아오지 않았다");
      }
      HeartbeatScenario.await(tick);
    }

    @Test
    @DisplayName("renew가 끝나기 전에는 돌아오지 않는다")
    void waitsForInFlightRenew() {
      // when
      BackgroundThread stopping = scenario.stopInBackground();

      // then
      assertThat(stopping.isAlive()).isTrue();
    }

    @Test
    @DisplayName("renew가 끝나면 돌아온다")
    void returnsAfterRenew() throws Exception {
      // given
      BackgroundThread stopping = scenario.stopInBackground();

      // when
      scenario.releaseRenew();

      // then
      assertThat(stopping.returnsWithin(WAIT_LIMIT)).isTrue();
    }

    @Test
    @DisplayName("정지 뒤 해제된 점유를 되살리지 않는다")
    void doesNotReviveReleasedClaim() throws Exception {
      // given
      givenStoppedAfterRenew();
      scenario.releaseClaim();

      // when
      scenario.tick();

      // then
      assertThat(scenario.storedClaimActive()).isFalse();
    }
  }

  @Nested
  @DisplayName("stop한 뒤")
  class AfterStop {
    @BeforeEach
    void givenStopped() {
      scenario.start();
      scenario.stop();
    }

    @Test
    @DisplayName("tick은 점유 저장소를 부르지 않는다(타이머 취소)")
    void timerIsCancelled() {
      // given
      int renewsAfterStop = scenario.renewCalls();

      // when
      scenario.tick();

      // then
      assertThat(scenario.renewCalls()).isEqualTo(renewsAfterStop);
    }

    @Test
    @DisplayName("스케줄러 슬롯을 반환한다")
    void releasesSchedulerSlot() {
      // then
      assertThat(scenario.liveTasks()).isZero();
    }

    @Test
    @DisplayName("다른 스레드의 뒤늦은 renew는 점유를 연장하지 않는다")
    void lateRenewDoesNotExtend() throws Exception {
      // given
      scenario.advanceClock(WITHIN_TTL);

      // when
      scenario.renewFromOtherThread();

      // then
      assertThat(scenario.storedExpiry()).isEqualTo(scenario.originalClaim().expiresAt());
    }

    // stop이 돌아온 뒤에는 잠금을 쥔 renew가 없어 "잠금 대기 없이"는 만들 수 없는 상황이다. 대신 저장소를 부르지 않는 것을 본다
    // (돌아오는 것은 renewFromOtherThread의 대기 상한이 지킨다).
    @Test
    @DisplayName("다른 스레드의 뒤늦은 renew는 점유 저장소를 부르지 않는다")
    void lateRenewDoesNotCallStore() throws Exception {
      // given
      int renewsAfterStop = scenario.renewCalls();
      scenario.advanceClock(WITHIN_TTL);

      // when
      scenario.renewFromOtherThread();

      // then
      assertThat(scenario.renewCalls()).isEqualTo(renewsAfterStop);
    }
  }
}
