package io.scos.engine.support.call;

import static io.scos.engine.support.call.ProviderCallScenario.OPERATION;
import static io.scos.engine.support.call.ProviderCallScenario.REQUEST_TIMEOUT;
import static io.scos.engine.support.call.ProviderCallScenario.Script.VALUE;
import static io.scos.engine.support.call.ProviderCallScenario.Script.alwaysFailsWith;
import static io.scos.engine.support.call.ProviderCallScenario.Script.alwaysUnavailable;
import static io.scos.engine.support.call.ProviderCallScenario.Script.classifierEscapes;
import static io.scos.engine.support.call.ProviderCallScenario.Script.failsThenSucceeds;
import static io.scos.engine.support.call.ProviderCallScenario.Script.interruptsThread;
import static io.scos.engine.support.call.ProviderCallScenario.Script.rateLimitedOnce;
import static io.scos.engine.support.call.ProviderCallScenario.Script.refusesHeader;
import static io.scos.engine.support.call.ProviderCallScenario.Script.succeeds;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.Tags;
import io.scos.engine.support.call.ProviderCallScenario.Outcome;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.CancellationToken;
import io.scos.support.BackgroundThread;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F7 재시도 정책(가짜 시계·가짜 대기, 실제 대기 0): 비재시도 즉시 실패, 5회 상한, 600s 예산, retryAfter 우선, 취소, 시도별 timeout. */
@DisplayName("공급자 호출은 정해진 재시도·예산·취소 규칙으로 부른다(F7)")
class ProviderCallExecutorTest {
  static final Duration ALMOST_DEFAULT_BUDGET = Duration.ofSeconds(590);
  static final Duration SMALL_BUDGET = Duration.ofSeconds(10);
  static final Duration MEDIUM_BUDGET = Duration.ofSeconds(100);

  /** 예산 10s 안에서 한 번 시도하면 다음 지연이 예산을 넘는 시도 시간. */
  static final Duration SLOW_ATTEMPT = Duration.ofSeconds(9);

  /** 경과 8s + 첫 지연 2s = 예산 10s. */
  static final Duration ATTEMPT_REACHING_BUDGET = Duration.ofSeconds(8);

  static final Duration SLOW_FIRST_ATTEMPT = Duration.ofSeconds(80);
  static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);

  /** 100s − (80s + 2s). */
  static final Duration REMAINING_BUDGET = Duration.ofSeconds(18);

  static final Duration LONG_WAIT = Duration.ofSeconds(30);

  final ProviderCallScenario scenario = new ProviderCallScenario();

  @Nested
  @DisplayName("재시도하지 않는 실패(authentication_failed)가 나면")
  class NonRetryable {
    @Test
    @DisplayName("그 종류로 실패한다")
    void failsWithItsKind() {
      // when
      Outcome outcome = scenario.call(alwaysFailsWith(Kind.AUTHENTICATION_FAILED));

      // then
      assertThat(outcome.failureKind()).contains(Kind.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("한 번만 부른다")
    void callsOnce() {
      // when
      Outcome outcome = scenario.call(alwaysFailsWith(Kind.AUTHENTICATION_FAILED));

      // then
      assertThat(outcome.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("기다리지 않는다")
    void doesNotWait() {
      // when
      scenario.call(alwaysFailsWith(Kind.AUTHENTICATION_FAILED));

      // then
      assertThat(scenario.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("보낼 수 없는 헤더 값으로 거부된 호출은(분류기가 전송 실패로 보더라도)")
  class UnsendableHeader {
    @Test
    @DisplayName("invalid_configuration으로 실패한다")
    void failsAsInvalidConfiguration() {
      // when
      Outcome outcome = scenario.call(refusesHeader());

      // then
      assertThat(outcome.failureKind()).contains(Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("재시도하지 않는다")
    void callsOnce() {
      // when
      Outcome outcome = scenario.call(refusesHeader());

      // then
      assertThat(outcome.calls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("재시도 가능한 실패가 계속되면")
  class RetryableKeepsFailing {
    static final List<Duration> BACKOFFS =
        List.of(
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16));

    @Test
    @DisplayName("5회까지만 시도한다(무한 재시도 없음)")
    void stopsAfterFiveAttempts() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable());

      // then
      assertThat(outcome.calls()).isEqualTo(5);
    }

    @Test
    @DisplayName("마지막 실패의 종류로 실패한다")
    void failsWithLastKind() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable());

      // then
      assertThat(outcome.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("시도 사이 지연은 2s·4s·8s·16s다(random 0)")
    void backsOffExponentially() {
      // when
      scenario.call(alwaysUnavailable());

      // then
      assertThat(scenario.waits()).containsExactlyElementsOf(BACKOFFS);
    }
  }

  @Nested
  @DisplayName("두 번 실패 뒤 성공하면")
  class SucceedsAfterRetries {
    @Test
    @DisplayName("성공한 값을 돌려준다")
    void returnsValue() {
      // when
      Outcome outcome = scenario.call(failsThenSucceeds(2));

      // then
      assertThat(outcome.value()).contains(VALUE);
    }

    @Test
    @DisplayName("시도마다 timeout은 요청 timeout(30s)이다")
    void eachAttemptGetsRequestTimeout() {
      // when
      Outcome outcome = scenario.call(failsThenSucceeds(2));

      // then
      assertThat(outcome.timeouts())
          .containsExactly(REQUEST_TIMEOUT, REQUEST_TIMEOUT, REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("예산은 호출마다 새로 시작한다(앞 호출의 경과가 다음 호출을 줄이지 않는다)")
    void budgetIsPerCall() {
      // given: 앞 호출이 끝나고 기본 예산(600s)이 거의 지났다
      scenario.call(failsThenSucceeds(2));
      scenario.advanceClock(ALMOST_DEFAULT_BUDGET);

      // when
      Outcome next = scenario.call(succeeds());

      // then
      assertThat(next.timeouts()).containsExactly(REQUEST_TIMEOUT);
    }
  }

  @Nested
  @DisplayName("예산 10s에 시도마다 9s가 걸리면")
  class SlowAttemptsInSmallBudget {
    @BeforeEach
    void smallBudget() {
      scenario.budget(SMALL_BUDGET);
    }

    @Test
    @DisplayName("첫 시도 timeout은 min(30s, 예산)이다")
    void firstTimeoutIsCappedByBudget() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable().eachAttemptTakes(SLOW_ATTEMPT));

      // then
      assertThat(outcome.timeouts()).containsExactly(SMALL_BUDGET);
    }

    @Test
    @DisplayName("예산을 넘기는 지연은 기다리지 않는다")
    void doesNotWaitPastBudget() {
      // when
      scenario.call(alwaysUnavailable().eachAttemptTakes(SLOW_ATTEMPT));

      // then
      assertThat(scenario.waits()).isEmpty();
    }

    @Test
    @DisplayName("다음 지연이 예산을 넘으면 마지막 시도의 dependency_unavailable로 끝난다")
    void failsWhenDelayExceedsBudget() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable().eachAttemptTakes(SLOW_ATTEMPT));

      // then
      assertThat(outcome.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("예산 10s에 시도가 8s 걸려 경과 + 첫 지연이 예산과 같으면")
  class DelayReachingBudget {
    @BeforeEach
    void smallBudget() {
      scenario.budget(SMALL_BUDGET);
    }

    @Test
    @DisplayName("기다리지 않는다(경계 포함)")
    void boundaryIsInclusive() {
      // when
      scenario.call(alwaysUnavailable().eachAttemptTakes(ATTEMPT_REACHING_BUDGET));

      // then
      assertThat(scenario.waits()).isEmpty();
    }

    @Test
    @DisplayName("마지막 시도의 dependency_unavailable로 끝난다")
    void failsAtBoundary() {
      // when
      Outcome outcome =
          scenario.call(alwaysUnavailable().eachAttemptTakes(ATTEMPT_REACHING_BUDGET));

      // then
      assertThat(outcome.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("예산 100s에 첫 시도가 80s 걸린 뒤 성공하면")
  class SlowFirstAttemptInMediumBudget {
    @BeforeEach
    void mediumBudget() {
      scenario.budget(MEDIUM_BUDGET);
    }

    @Test
    @DisplayName("첫 지연은 2s이고, 둘째 시도 timeout은 남은 예산으로 줄어든다")
    void laterTimeoutShrinksToRemainingBudget() {
      // when
      Outcome outcome = scenario.call(failsThenSucceeds(1).firstAttemptTakes(SLOW_FIRST_ATTEMPT));

      // then: 남은 예산 = maxElapsed − (첫 시도 80s + 첫 지연 2s)
      assertThat(scenario.waits()).containsExactly(FIRST_BACKOFF);
      assertThat(outcome.timeouts()).containsExactly(REQUEST_TIMEOUT, REMAINING_BUDGET);
    }
  }

  @Nested
  @DisplayName("rate_limited에 retryAfter가 있으면")
  class RetryAfter {
    static final Duration RETRY_AFTER = Duration.ofMillis(1500);

    @Test
    @DisplayName("지터 지연 대신 retryAfter만큼 기다린다")
    void waitsRetryAfter() {
      // when
      scenario.call(rateLimitedOnce(RETRY_AFTER));

      // then
      assertThat(scenario.waits()).containsExactly(RETRY_AFTER);
    }

    @Test
    @DisplayName("retryAfter 0이면 다시 부른다")
    void zeroRetries() {
      // when
      Outcome outcome = scenario.call(rateLimitedOnce(Duration.ZERO));

      // then
      assertThat(outcome.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("retryAfter 0이면 기다리지 않는다")
    void zeroRetriesImmediately() {
      // when
      scenario.call(rateLimitedOnce(Duration.ZERO));

      // then
      assertThat(scenario.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("예산 10s에 rate_limited의 retryAfter가 60s로 남은 예산보다 길면")
  class RetryAfterBeyondBudget {
    static final Duration RETRY_AFTER = Duration.ofSeconds(60);

    @BeforeEach
    void smallBudget() {
      scenario.budget(SMALL_BUDGET);
    }

    @Test
    @DisplayName("기다리지 않는다(retryAfter보다 예산이 먼저다)")
    void doesNotWaitPastBudget() {
      // when
      scenario.call(rateLimitedOnce(RETRY_AFTER));

      // then
      assertThat(scenario.waits()).isEmpty();
    }

    @Test
    @DisplayName("다시 부르지 않고 rate_limited로 바로 실패한다")
    void failsRateLimitedWithoutRetry() {
      // when
      Outcome outcome = scenario.call(rateLimitedOnce(RETRY_AFTER));

      // then
      assertThat(outcome.calls()).isEqualTo(1);
      assertThat(outcome.failureKind()).contains(Kind.RATE_LIMITED);
    }
  }

  static CancellationToken cancelledToken() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    return token;
  }

  @Nested
  @DisplayName("재시도 대기 중 취소되면")
  class CancelledDuringWait {
    @BeforeEach
    void cancelWhileWaiting() {
      scenario.waitsGetCancelled();
    }

    @Test
    @DisplayName("cancelled로 즉시 실패한다")
    void cancelledDuringWait() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable(), new CancellationToken());

      // then
      assertThat(outcome.failureKind()).contains(Kind.CANCELLED);
    }

    @Test
    @DisplayName("다시 부르지 않는다")
    void doesNotCallAgain() {
      // when
      Outcome outcome = scenario.call(alwaysUnavailable(), new CancellationToken());

      // then
      assertThat(outcome.calls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("이미 취소된 토큰이면")
  class AlreadyCancelled {
    @Test
    @DisplayName("cancelled로 끝난다")
    void endsCancelled() {
      // when
      Outcome outcome = scenario.call(succeeds(), cancelledToken());

      // then
      assertThat(outcome.failureKind()).contains(Kind.CANCELLED);
    }

    @Test
    @DisplayName("공급자를 부르지 않는다")
    void alreadyCancelledDoesNotCall() {
      // when
      Outcome outcome = scenario.call(succeeds(), cancelledToken());

      // then
      assertThat(outcome.calls()).isZero();
    }
  }

  @Nested
  @DisplayName("호출이 스레드를 인터럽트한 채 실패하면")
  class CallInterruptsThread {
    @Test
    @DisplayName("cancelled로 끝난다")
    void interruptedCallIsCancellation() {
      // when
      Outcome outcome = scenario.call(interruptsThread());

      // then
      assertThat(outcome.failureKind()).contains(Kind.CANCELLED);
    }

    @Test
    @DisplayName("재시도하지 않는다")
    void doesNotRetry() {
      // when
      Outcome outcome = scenario.call(interruptsThread());

      // then
      assertThat(outcome.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("기다리지 않는다")
    void doesNotWait() {
      // when
      scenario.call(interruptsThread());

      // then
      assertThat(scenario.waits()).isEmpty();
    }

    @Test
    @DisplayName("스레드의 인터럽트 표시는 보존된다")
    void interruptFlagIsKept() {
      // when
      Outcome outcome = scenario.call(interruptsThread());

      // then
      assertThat(outcome.threadStillInterrupted()).isTrue();
    }
  }

  @Nested
  @DisplayName("인터럽트된 스레드에서 부르면")
  class OnInterruptedThread {
    @Test
    @DisplayName("cancelled로 끝난다")
    void endsCancelled() {
      // when
      Outcome outcome = scenario.callOnInterruptedThread(succeeds());

      // then
      assertThat(outcome.failureKind()).contains(Kind.CANCELLED);
    }

    @Test
    @DisplayName("공급자를 부르지 않는다")
    void interruptedThreadDoesNotCall() {
      // when
      Outcome outcome = scenario.callOnInterruptedThread(succeeds());

      // then
      assertThat(outcome.calls()).isZero();
    }
  }

  @Nested
  @DisplayName("다른 스레드가 토큰을 취소하면")
  class CancelledFromAnotherThread {
    static final Duration PROMPT = Duration.ofSeconds(5);

    @Test
    @DisplayName("awaitCancellation은 취소라고 답하고, 곧바로 깨어난다")
    void tokenWakesOnCancel() throws Exception {
      // given
      CancellationToken token = new CancellationToken();
      long started = System.nanoTime();

      // when
      BackgroundThread.start("canceller", token::cancel);
      boolean cancelled = token.awaitCancellation(LONG_WAIT);

      // then: 시간 초과가 아니라 취소로 깨어났다
      assertThat(cancelled).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(PROMPT);
    }
  }

  @Nested
  @DisplayName("분류기가 예외를 던지면")
  class ClassifierEscapes {
    @Test
    @DisplayName("그 예외가 래핑 없이 그대로 전파된다")
    void classifierEscapePropagates() {
      // when·then
      assertThatThrownBy(() -> scenario.call(classifierEscapes()))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("그 예외로 끝나고, 재시도하지 않는다")
    void classifierEscapeIsNotRetried() {
      // when
      Throwable escaped = catchThrowable(() -> scenario.call(classifierEscapes()));

      // then
      assertThat(escaped).isInstanceOf(UnsupportedOperationException.class);
      assertThat(scenario.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("기본 재시도 대기(awaitCancellation)는")
  class DefaultWaiter {
    /** 대기를 끝까지 기다리지 않아도 되는 짧은 지연. */
    static final Duration SHORT_DELAY = Duration.ofMillis(1);

    /** 실제로 기다렸는지 잴 만큼 길고, 테스트가 느려지지 않을 만큼 짧은 지연. */
    static final Duration MEASURABLE_DELAY = Duration.ofMillis(50);

    /** 취소되지 않는 토큰으로 기본 대기를 부르고 돌아올 때까지 걸린 시간. */
    static Duration timeToWaitOut(Duration delay) {
      long started = System.nanoTime();
      boolean cancelled = ProviderCallExecutor.awaitCancellation(delay, new CancellationToken());
      if (cancelled) throw new IllegalStateException("given 준비 실패: 취소되지 않은 대기여야 한다");
      return Duration.ofNanos(System.nanoTime() - started);
    }

    @Test
    @DisplayName("취소되지 않으면 지연이 다 지날 때까지 돌아오지 않는다")
    void waitsOutTheDelay() {
      // when
      Duration took = timeToWaitOut(MEASURABLE_DELAY);

      // then
      assertThat(took).isGreaterThanOrEqualTo(MEASURABLE_DELAY);
    }

    @Test
    @DisplayName("지연이 다 지나면 취소가 아니라고 답한다")
    void elapsedDelayIsNotCancellation() {
      // when
      boolean cancelled =
          ProviderCallExecutor.awaitCancellation(SHORT_DELAY, new CancellationToken());

      // then
      assertThat(cancelled).isFalse();
    }

    @Test
    @DisplayName("취소된 토큰이면 취소라고 답한다")
    void cancelledTokenIsCancellation() {
      // when
      boolean cancelled = ProviderCallExecutor.awaitCancellation(LONG_WAIT, cancelledToken());

      // then
      assertThat(cancelled).isTrue();
    }
  }

  @Nested
  @DisplayName("기본 실행기(defaults)로 부르면")
  class WithDefaults {
    @Test
    @DisplayName("성공한 값을 돌려준다")
    void returnsValue() {
      // when
      Outcome outcome = scenario.callWithDefaults(succeeds());

      // then
      assertThat(outcome.value()).contains(VALUE);
    }

    @Test
    @DisplayName("시도 timeout은 요청 timeout 30s다")
    void defaultsUseRequestTimeout() {
      // when
      Outcome outcome = scenario.callWithDefaults(succeeds());

      // then
      assertThat(outcome.timeouts()).containsExactly(REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("requestTimeout()은 정책의 요청 timeout이다")
    void exposesPolicyRequestTimeout() {
      // given
      ProviderCallExecutor executor =
          ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls());

      // when
      Duration timeout = executor.requestTimeout();

      // then
      assertThat(timeout).isEqualTo(REQUEST_TIMEOUT);
    }
  }

  @Nested
  @DisplayName("기본 정책(defaults)이면")
  class DefaultPolicy {
    static final Duration DEFAULT_BUDGET = Duration.ofMinutes(10);

    @ParameterizedTest(name = "attempt {0}, random {1} → {2}")
    @CsvSource({
      "1, 0.0, PT2S",
      "1, 0.5, PT3S",
      "1, 0.999, PT3.998S",
      "2, 0.5, PT6S",
      "4, 0.0, PT16S",
      "5, 0.0, PT30S",
      "6, 0.999, PT59.97S"
    })
    @DisplayName("지터 지연은 [cap/2, cap)이다(cap은 60s와 4s·2^(n−1) 중 작은 값)")
    void jitterRange(int attempt, double random, Duration expected) {
      // given
      ProviderCallPolicy policy = ProviderCallPolicy.defaults(() -> random);

      // when
      Duration delay = policy.retryDelay(CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, "x"), attempt);

      // then
      assertThat(delay).isEqualTo(expected);
    }

    @Test
    @DisplayName("요청 timeout 30s·5회·600s다")
    void defaults() {
      // when
      ProviderCallPolicy p = ProviderCallPolicy.defaults(() -> 0.0);

      // then
      assertThat(p.requestTimeout()).isEqualTo(REQUEST_TIMEOUT);
      assertThat(p.maxAttempts()).isEqualTo(5);
      assertThat(p.maxElapsed()).isEqualTo(DEFAULT_BUDGET);
    }
  }

  @Nested
  @DisplayName("maxAttempts 0으로 정책을 만들면")
  class ZeroAttemptsPolicy {
    @Test
    @DisplayName("거부된다")
    void rejectsZeroAttempts() {
      // when·then
      assertThatThrownBy(
              () -> new ProviderCallPolicy(REQUEST_TIMEOUT, 0, REQUEST_TIMEOUT, () -> 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("지표를 기록하는 실행기로 부르면(scos.provider.calls)")
  class CallMetrics {
    static Tags tags(String outcome, String failureKind) {
      return ProviderCallTags.of(OPERATION, outcome, failureKind);
    }

    @BeforeEach
    void metered() {
      scenario.metered();
    }

    @Test
    @DisplayName("재시도한 실패마다 retry를 센다")
    void countsRetries() {
      // when
      scenario.call(failsThenSucceeds(2));

      // then
      assertThatMetrics(scenario.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS, tags("retry", "dependency_unavailable"), 2);
    }

    @Test
    @DisplayName("성공한 시도를 ok로 센다")
    void countsSuccess() {
      // when
      scenario.call(failsThenSucceeds(2));

      // then
      assertThatMetrics(scenario.metrics())
          .hasCounter(ProviderCallMetrics.PROVIDER_CALLS, tags("ok", "none"), 1);
    }

    @Test
    @DisplayName("재시도하지 않는 실패를 failed로 센다")
    void countsFailure() {
      // when
      scenario.call(alwaysFailsWith(Kind.AUTHENTICATION_FAILED));

      // then
      assertThatMetrics(scenario.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS, tags("failed", "authentication_failed"), 1);
    }

    @Test
    @DisplayName("취소된 호출을 cancelled로 센다")
    void countsCancellation() {
      // when
      scenario.call(succeeds(), cancelledToken());

      // then
      assertThatMetrics(scenario.metrics())
          .hasCounter(ProviderCallMetrics.PROVIDER_CALLS, tags("cancelled", "none"), 1);
    }
  }
}
