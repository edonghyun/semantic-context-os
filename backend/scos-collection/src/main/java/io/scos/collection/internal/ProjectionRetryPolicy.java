package io.scos.collection.internal;

import java.time.Duration;

/**
 * 투영 재시도 정책(불변식 7, ADR-0018 §6): 실패마다 backoff = min(max, base·2^(attempts−1)), attempts가
 * maxAttempts에 이르면 parked(새 데이터가 올 때까지 시도하지 않음, last_error로 보고). 정책은 유스케이스가 소유하고 포트는 값만 저장한다.
 */
public record ProjectionRetryPolicy(Duration base, Duration max, int maxAttempts) {
  public ProjectionRetryPolicy {
    if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
  }

  public static ProjectionRetryPolicy defaults() {
    return new ProjectionRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 10);
  }

  public Duration backoff(int attempts) {
    long ms = base.toMillis() * (1L << Math.min(20, Math.max(0, attempts - 1)));
    return Duration.ofMillis(Math.min(max.toMillis(), ms));
  }

  public boolean parks(int attempts) {
    return attempts >= maxAttempts;
  }
}
