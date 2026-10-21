package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 조건 대기의 계약(TESTING §7): 조건이 채워지면 돌아오고, 상한을 넘기면 무엇을 기다렸는지 밝혀 실패하고, 인터럽트되면 상한을 기다리지 않고 중단을 밝힌다(JUnit
 * {@code @Timeout}이 대기를 끊을 수 있어야 한다).
 */
@DisplayName("조건 대기는 채워지면 돌아오고 상한·인터럽트에 이름을 밝혀 끝난다(TESTING §7)")
class ChildJvmWaitTest {
  /** 조건이 채워지지 않는 대기의 hang 방지 상한 — 테스트가 오래 걸리지 않을 만큼 짧다. */
  static final Duration SHORT_LIMIT = Duration.ofMillis(200);

  /** 조건이 몇 번 만에 채워지는 경우의 넉넉한 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /** SHORT_LIMIT로 실패하는 대기가 끝나야 하는 느슨한 상한 — 상한 값이 부풀면(예: 단위 실수) 잡는다. 바쁜 기기의 지연을 넉넉히 덮는다. */
  static final Duration SHORT_LIMIT_FAILS_WITHIN = SHORT_LIMIT.multipliedBy(10);

  /** 세 번째 확인에서 참이 되는 조건 — 폴링으로 다시 확인하는지 본다. */
  static BooleanSupplier trueOnThirdCheck() {
    AtomicInteger checks = new AtomicInteger();
    return () -> checks.incrementAndGet() >= 3;
  }

  /** 대기가 실패로 끝날 때까지 걸린 시간. */
  static Duration timeToFail(ThrowingCallable wait) {
    long started = System.nanoTime();
    Throwable thrown = catchThrowable(wait);
    if (thrown == null) throw new IllegalStateException("given 준비 실패: 대기가 실패해야 한다");
    return Duration.ofNanos(System.nanoTime() - started);
  }

  /** 부른 스레드에 인터럽트 표시를 세운다 — JUnit {@code @Timeout}이 테스트 스레드를 끊는 것과 같다. */
  static void givenInterrupted() {
    Thread.currentThread().interrupt();
  }

  @Nested
  @DisplayName("Eventually.await는")
  class EventuallyAwait {
    @Test
    @DisplayName("조건이 나중에 참이 되면 돌아온다")
    void returnsOnceConditionHolds() {
      // when·then
      assertThatCode(() -> Eventually.await("세 번째 확인", trueOnThirdCheck(), WAIT_LIMIT))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상한을 넘기면 조건 이름을 밝혀 실패한다")
    void failsWithConditionName() {
      // when·then
      assertThatThrownBy(() -> Eventually.await("오지 않는 조건", () -> false, SHORT_LIMIT))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("오지 않는 조건");
    }

    @Test
    @DisplayName("상한을 넘기면 상한 근처에서 끝난다")
    void failsNearItsLimit() {
      // when
      Duration took = timeToFail(() -> Eventually.await("오지 않는 조건", () -> false, SHORT_LIMIT));

      // then
      assertThat(took).isBetween(SHORT_LIMIT, SHORT_LIMIT_FAILS_WITHIN);
    }
  }

  @Nested
  @DisplayName("부른 스레드가 인터럽트되면")
  class WhenInterrupted {
    @AfterEach
    void clearInterrupt() {
      Thread.interrupted();
    }

    @Test
    @DisplayName("ChildJvm.awaitMarker는 InterruptedException으로 끝난다")
    void awaitMarkerThrowsInterrupted(@TempDir Path dir) {
      // given
      givenInterrupted();

      // when
      Throwable thrown =
          catchThrowable(() -> ChildJvm.awaitMarker(dir.resolve("never"), WAIT_LIMIT.toMillis()));

      // then
      assertThat(thrown).isInstanceOf(InterruptedException.class);
    }

    @Test
    @DisplayName("Eventually.await는 중단을 밝혀 실패한다")
    void eventuallyNamesInterruption() {
      // given
      givenInterrupted();

      // when
      Throwable thrown =
          catchThrowable(() -> Eventually.await("오지 않는 조건", () -> false, WAIT_LIMIT));

      // then
      assertThat(thrown).hasMessage("interrupted while waiting for: 오지 않는 조건");
    }

    @Test
    @DisplayName("Eventually.await는 인터럽트 표시를 되살린다")
    void eventuallyRestoresInterruptFlag() {
      // given
      givenInterrupted();

      // when
      Throwable thrown =
          catchThrowable(() -> Eventually.await("오지 않는 조건", () -> false, WAIT_LIMIT));

      // then
      if (thrown == null) throw new IllegalStateException("given 준비 실패: 대기가 실패해야 한다");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
  }

  @Nested
  @DisplayName("ChildJvm.awaitMarker는")
  class AwaitMarker {
    @Test
    @DisplayName("마커 파일이 생기면 돌아온다")
    void returnsWhenMarkerExists(@TempDir Path dir) throws Exception {
      // given
      Path marker = dir.resolve("ready");
      ChildJvm.touch(marker);

      // when·then
      assertThatCode(() -> ChildJvm.awaitMarker(marker, WAIT_LIMIT.toMillis()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상한을 넘기면 마커 경로를 밝혀 실패한다")
    void failsWithMarkerPath(@TempDir Path dir) {
      // given
      Path marker = dir.resolve("never");

      // when·then
      assertThatThrownBy(() -> ChildJvm.awaitMarker(marker, SHORT_LIMIT.toMillis()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(marker.toString());
    }
  }
}
