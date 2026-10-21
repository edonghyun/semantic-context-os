package io.scos.engine.support.call;

import io.scos.spi.connector.CallFailure;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * F7 재시도 정책(TS DefaultProviderCallPolicy): 요청 timeout 30s, 최대 5회, 총 600s. 지연은
 * rate_limited.retryAfter 우선, 아니면 cap = min(60s, 4s·2^(n−1))에서 [cap/2, cap) 지터. 난수는 주입(테스트 결정론).
 */
public record ProviderCallPolicy(
    Duration requestTimeout, int maxAttempts, Duration maxElapsed, DoubleSupplier random) {
  /** F7 기본 요청 timeout(TS DEFAULT_REQUEST_TIMEOUT_MS). */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  public static ProviderCallPolicy defaults(DoubleSupplier random) {
    return new ProviderCallPolicy(DEFAULT_REQUEST_TIMEOUT, 5, Duration.ofMinutes(10), random);
  }

  public ProviderCallPolicy {
    if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
  }

  public Duration retryDelay(CallFailure failure, int failedAttempt) {
    if (failure.kind() == CallFailure.Kind.RATE_LIMITED && failure.retryAfter().isPresent())
      return failure.retryAfter().get();
    long capMs = Math.min(60_000L, 4_000L * (1L << (failedAttempt - 1)));
    return Duration.ofMillis((long) Math.floor(capMs / 2.0 + random.getAsDouble() * (capMs / 2.0)));
  }
}
