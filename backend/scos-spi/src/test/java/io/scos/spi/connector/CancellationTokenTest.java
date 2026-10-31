package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.BackgroundThread;
import io.scos.support.Eventually;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 명시 취소 토큰(ADR-0008): 대기는 취소·인터럽트로 곧 깨어나고, 잠금을 쥔 채 끝나지 않는다. 다른 스레드의 대기·취소는 상한 있는 합류로 본다 — 깨우지 못하거나
 * 잠금을 놓지 않는 결함이 끝없는 대기가 아니라 단언 실패가 되게(PIT 감시 15s보다 짧게).
 */
@DisplayName("취소 토큰의 대기는 취소·인터럽트로 깨어나고 잠금을 남기지 않는다")
class CancellationTokenTest {
  /** 깨어나지 않으면 끝날 대기 — 아래 PROMPT보다 길고 PIT 감시보다 짧다. */
  static final Duration LONG_WAIT = Duration.ofSeconds(10);

  /** 깨어났다고 볼 상한. */
  static final Duration PROMPT = Duration.ofSeconds(5);

  /** 대기를 끝까지 기다려도 되는 짧은 지연. */
  static final Duration SHORT_WAIT = Duration.ofMillis(1);

  final CancellationToken token = new CancellationToken();
  final AtomicBoolean answer = new AtomicBoolean();

  /** 다른 스레드에서 LONG_WAIT 동안 기다리게 하고, 그 스레드가 토큰 안에서 대기에 들어갈 때까지 기다린다. */
  BackgroundThread waiterInside() {
    BackgroundThread waiter =
        BackgroundThread.start("waiter", () -> answer.set(token.awaitCancellation(LONG_WAIT)));
    Eventually.await(
        "토큰 안의 대기", () -> waiter.isWaitingIn(CancellationToken.class, "awaitCancellation"), PROMPT);
    return waiter;
  }

  @Nested
  @DisplayName("취소되지 않은 채 대기 시간이 지나면")
  class TimesOut {
    @Test
    @DisplayName("취소가 아니라고 답한다")
    void answersNotCancelled() {
      // when
      boolean cancelled = token.awaitCancellation(SHORT_WAIT);

      // then
      assertThat(cancelled).isFalse();
    }

    @Test
    @DisplayName("잠금을 놓아 다른 스레드가 취소할 수 있다")
    void releasesLock() throws InterruptedException {
      // given: 다른 스레드에서 기다리다 끝난다(잠금은 스레드마다라 같은 스레드의 취소로는 보이지 않는다)
      BackgroundThread waiter =
          BackgroundThread.start("waiter", () -> token.awaitCancellation(SHORT_WAIT));
      assertThat(waiter.returnsWithin(PROMPT)).as("given: 대기가 끝난다").isTrue();

      // when
      BackgroundThread canceller = BackgroundThread.start("canceller", token::cancel);

      // then
      assertThat(canceller.returnsWithin(PROMPT)).isTrue();
    }
  }

  @Nested
  @DisplayName("기다리는 중에 다른 스레드가 취소하면")
  class CancelledWhileWaiting {
    @Test
    @DisplayName("기다리던 스레드가 곧 깨어나 취소라고 답한다")
    void waiterWakesCancelled() throws InterruptedException {
      // given
      BackgroundThread waiter = waiterInside();

      // when
      token.cancel();

      // then
      assertThat(waiter.returnsWithin(PROMPT)).isTrue();
      assertThat(answer).isTrue();
    }
  }

  @Nested
  @DisplayName("기다리던 스레드가 인터럽트되면")
  class Interrupted {
    @Test
    @DisplayName("취소라고 답하고 인터럽트 표시를 남긴다")
    void answersCancelledAndKeepsFlag() throws InterruptedException {
      // given
      BackgroundThread waiter = waiterInside();

      // when
      waiter.interruptAndJoin(PROMPT);

      // then
      assertThat(answer).isTrue();
      assertThat(waiter.wasInterruptedAtEnd()).isTrue();
    }
  }
}
