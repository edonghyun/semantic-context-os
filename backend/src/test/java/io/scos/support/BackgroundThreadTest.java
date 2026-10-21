package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 다른 스레드 호출 어휘 자체의 계약(TESTING §4.2 C7): 대기 지점·인터럽트 합류·반환 여부를 정확히 보고하고, 상한 안에 끝나지 않으면 조용히 넘어가지 않는다.
 */
@DisplayName("다른 스레드 호출은 상태를 정확히 보고하고 상한을 넘기면 멈춘다(TESTING §7)")
class BackgroundThreadTest {
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  static final Duration SHORT_LIMIT = Duration.ofMillis(200);
  static final long LONG_WAIT_MINUTES = 1;

  final CountDownLatch never = new CountDownLatch(1);

  /** 이름 있는 대기 지점: 시간 상한이 있는 대기. */
  static void waitsAtGate(CountDownLatch gate) throws InterruptedException {
    gate.await(LONG_WAIT_MINUTES, TimeUnit.MINUTES);
  }

  @AfterEach
  void release() {
    never.countDown();
  }

  @Nested
  @DisplayName("인터럽트로 깨는 대기 중인 호출을 인터럽트하면")
  class InterruptibleWait {
    BackgroundThread waiter;

    @BeforeEach
    void givenWaiting() throws InterruptedException {
      waiter = BackgroundThread.start("waiter", never::await);
      Eventually.await("대기에 들어감", waiter::isBlockedOrDone, WAIT_LIMIT);
      waiter.interruptAndJoin(WAIT_LIMIT);
    }

    @Test
    @DisplayName("정상 반환이 아니다")
    void didNotReturn() {
      // then
      assertThat(waiter.hasReturned()).isFalse();
    }

    @Test
    @DisplayName("끝난 시점에 인터럽트 표시가 남아 있다")
    void interruptedAtEnd() {
      // then
      assertThat(waiter.wasInterruptedAtEnd()).isTrue();
    }
  }

  @Nested
  @DisplayName("이름 있는 지점에서 시간 대기 중이면")
  class WaitingAtNamedPoint {
    BackgroundThread waiter;

    @BeforeEach
    void givenWaitingAtGate() {
      waiter = BackgroundThread.start("timed", () -> waitsAtGate(never));
    }

    @Test
    @DisplayName("그 지점에서 대기 중으로 보인다")
    void isWaitingThere() {
      // when·then: 상한 안에 보이지 않으면 Eventually가 실패한다
      Eventually.await(
          "waitsAtGate에서 대기",
          () -> waiter.isWaitingIn(BackgroundThreadTest.class, "waitsAtGate"),
          WAIT_LIMIT);
    }

    @Test
    @DisplayName("다른 지점에서는 대기 중으로 보이지 않는다")
    void isNotWaitingElsewhere() {
      // given
      Eventually.await(
          "given 준비 실패: waitsAtGate에서 대기",
          () -> waiter.isWaitingIn(BackgroundThreadTest.class, "waitsAtGate"),
          WAIT_LIMIT);

      // when
      boolean elsewhere = waiter.isWaitingIn(BackgroundThreadTest.class, "release");

      // then
      assertThat(elsewhere).isFalse();
    }
  }

  @Test
  @DisplayName("인터럽트를 무시하고 상한을 넘기면 given 준비 실패로 멈춘다")
  void failsWhenNotJoinedWithinLimit() {
    // given: 인터럽트를 보지 않고 풀릴 때까지 돈다
    AtomicBoolean released = new AtomicBoolean();
    BackgroundThread stubborn =
        BackgroundThread.start(
            "stubborn",
            () -> {
              while (!released.get()) Thread.onSpinWait();
            });

    // when·then
    try {
      assertThatThrownBy(() -> stubborn.interruptAndJoin(SHORT_LIMIT))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("given 준비 실패")
          .hasMessageContaining("stubborn");
    } finally {
      released.set(true);
    }
  }

  @Test
  @DisplayName("바로 끝나는 호출은 상한 안에 반환한다")
  void returnsWithin() throws InterruptedException {
    // when
    BackgroundThread quick = BackgroundThread.start("quick", () -> {});

    // then
    assertThat(quick.returnsWithin(WAIT_LIMIT)).isTrue();
  }
}
