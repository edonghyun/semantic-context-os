package io.scos.engine.support.call;

import io.scos.engine.support.call.ProviderCallMetrics.CallOutcome;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * 공급자 호출 실행기(TS DefaultProviderCallExecutor, F7·불변식 7): 시도마다 timeout = min(요청 timeout, 남은 예산), 비재시도
 * 종류는 즉시 실패, 재시도는 정책 지연 뒤(예산 초과면 실패), 취소는 대기 중에도 즉시. 보낼 수 없는 헤더 값의 거부({@link
 * UnsendableHeaderException})는 분류기와 무관하게 invalid_configuration(비재시도)이다. classify가 던지면 그대로 전파(재시도·래핑
 * 없음). 무한 재시도 없음. 취소 = 토큰 취소 또는 호출 스레드 인터럽트 — 토큰을 받지 못하는 호출자(임베더 포트 뒤의 Gemini 호출)도 데몬 종료(루프 스레드
 * 인터럽트)로 대기·재시도를 멈춘다(ADR-0020 §1). 인터럽트 표시는 지우지 않는다.
 */
public final class ProviderCallExecutor implements CallExecutor {
  /** 재시도 대기(테스트는 가짜로 대체). 취소되면 true. */
  @FunctionalInterface
  public interface Waiter {
    boolean waitOrCancel(Duration delay, CancellationToken cancel);
  }

  private final ProviderCallPolicy policy;
  private final Clock clock;
  private final Waiter waiter;
  private final ProviderCallMetrics metrics;

  public ProviderCallExecutor(
      ProviderCallPolicy policy, Clock clock, Waiter waiter, ProviderCallMetrics metrics) {
    this.policy = policy;
    this.clock = clock;
    this.waiter = waiter;
    this.metrics = metrics;
  }

  /** 기본 재시도 대기({@link Waiter}): 지연만큼 기다리되 취소되면 곧바로 깨어나 취소라고 답한다. */
  static boolean awaitCancellation(Duration delay, CancellationToken cancel) {
    return cancel.awaitCancellation(delay);
  }

  public static ProviderCallExecutor defaults(Clock clock, ProviderCallMetrics metrics) {
    return new ProviderCallExecutor(
        ProviderCallPolicy.defaults(Math::random),
        clock,
        ProviderCallExecutor::awaitCancellation,
        metrics);
  }

  /** 정책의 요청 timeout — 보통의 시도 timeout({@link ProviderHttp}가 이 값의 RestClient를 다시 쓴다). */
  @Override
  public Duration requestTimeout() {
    return policy.requestTimeout();
  }

  @Override
  public <T> T execute(
      String operation,
      Call<T> call,
      Function<Exception, CallFailure> classify,
      CancellationToken cancel) {
    if (cancelled(cancel)) throw cancellation(operation, Optional.empty());
    long startedAt = clock.millis();
    for (int attempt = 1; ; attempt++) {
      long remaining = policy.maxElapsed().toMillis() - (clock.millis() - startedAt);
      Duration timeout =
          Duration.ofMillis(
              Math.max(
                  1,
                  Math.min(
                      policy.requestTimeout().toMillis(),
                      remaining))); // JDK HttpRequest.timeout은 양수(리뷰 B-M-9)
      try {
        T result = call.invoke(timeout);
        metrics.providerCall(operation, CallOutcome.OK, Optional.empty());
        return result;
      } catch (Exception e) {
        if (cancelled(cancel)) throw cancellation(operation, Optional.empty());
        CallFailure failure =
            e instanceof UnsendableHeaderException refused
                ? CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, refused.getMessage())
                : classify.apply(e);
        if (!failure.retryable() || attempt == policy.maxAttempts())
          throw failed(operation, failure);
        Duration delay = policy.retryDelay(failure, attempt);
        if (clock.millis() - startedAt + delay.toMillis() >= policy.maxElapsed().toMillis())
          throw failed(operation, failure);
        metrics.providerCall(operation, CallOutcome.RETRY, Optional.of(failure.kind()));
        if (delay.toMillis() > 0 && waiter.waitOrCancel(delay, cancel))
          throw cancellation(operation, Optional.of(failure.kind()));
        if (cancelled(cancel)) throw cancellation(operation, Optional.of(failure.kind()));
      }
    }
  }

  private static boolean cancelled(CancellationToken cancel) {
    return cancel.isCancelled() || Thread.currentThread().isInterrupted();
  }

  private CallFailureException failed(String operation, CallFailure failure) {
    metrics.providerCall(operation, CallOutcome.FAILED, Optional.of(failure.kind()));
    return new CallFailureException(operation, failure);
  }

  private CallFailureException cancellation(String operation, Optional<CallFailure.Kind> lastKind) {
    metrics.providerCall(operation, CallOutcome.CANCELLED, lastKind);
    return cancellationException(operation);
  }

  private static CallFailureException cancellationException(String operation) {
    return new CallFailureException(
        operation,
        CallFailure.of(CallFailure.Kind.CANCELLED, "Provider call cancelled: " + operation));
  }
}
