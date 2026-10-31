package io.scos.collection.internal;

import java.time.Duration;

/**
 * 실패가 n번 이어진 뒤의 대기: min(max, base·2^(n−1)). 두 배로 늘리는 횟수는 {@code maxDoublings}에서 멈춘다 — 그 너머는 어차피
 * max이고 곱셈이 넘치지 않게 하는 상한이다.
 */
public record ExponentialBackoff(Duration base, Duration max, int maxDoublings) {
  public Duration afterFailures(int failures) {
    int doublings = Math.min(maxDoublings, Math.max(0, failures - 1));
    Duration grown = base.multipliedBy(1L << doublings);
    return grown.compareTo(max) > 0 ? max : grown;
  }
}
