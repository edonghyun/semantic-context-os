package io.scos.client;

import io.scos.client.collect.CollectionTask;
import io.scos.spi.connector.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** 클라이언트 실행에 쓰는 시계와 수집 관찰·재시도 대기 정책. */
record ClientOptions(
    Clock clock,
    UnaryOperator<CollectionTask> collectors,
    Runnable cycleEnded,
    Predicate<Duration> retryCancelled,
    Duration cycleInterval) {
  static ClientOptions defaults() {
    return defaults(Clock.systemUTC());
  }

  static ClientOptions defaults(Clock clock) {
    return new ClientOptions(
        clock,
        UnaryOperator.identity(),
        () -> {},
        CancellationToken.none()::awaitCancellation,
        CollectionCycles.CYCLE);
  }
}
