package io.scos.engine.support.call;

import java.time.Clock;
import java.time.Duration;

/** 호출 하나의 시간 예산: 만든 시각부터 정책의 maxElapsed까지. 시계는 물을 때마다 한 번 읽는다. */
final class CallBudget {
  private final ProviderCallPolicy policy;
  private final Clock clock;
  private final long startedAt;

  CallBudget(ProviderCallPolicy policy, Clock clock) {
    this.policy = policy;
    this.clock = clock;
    this.startedAt = clock.millis();
  }

  /** 이번 시도의 timeout = min(요청 timeout, 남은 예산), 적어도 1ms(JDK HttpRequest.timeout은 양수다). */
  Duration attemptTimeout() {
    long remaining = policy.maxElapsed().toMillis() - (clock.millis() - startedAt);
    return Duration.ofMillis(Math.max(1, Math.min(policy.requestTimeout().toMillis(), remaining)));
  }

  /** 지금부터 delay를 기다려도 예산 안인가(끝나는 시각이 예산과 같으면 넘은 것이다). */
  boolean allows(Duration delay) {
    return clock.millis() - startedAt + delay.toMillis() < policy.maxElapsed().toMillis();
  }
}
