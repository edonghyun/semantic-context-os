package io.scos.support;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;

/** 공급자 어댑터 테스트(임베더·생성기)가 함께 쓰는 가짜 서버 시작과 결정적 실행기(지터 0, 기본 대기는 취소만 본다). */
public final class ProviderCallFakes {
  public static final int MAX_ATTEMPTS = 3;
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration BUDGET = Duration.ofMinutes(2);

  private ProviderCallFakes() {}

  public static FakeHttpServer startHttp() {
    try {
      return new FakeHttpServer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static ProviderCallExecutor executor() {
    return executor((d, c) -> c.isCancelled(), BUDGET);
  }

  public static ProviderCallExecutor executor(ProviderCallExecutor.Waiter waiter, Duration budget) {
    return new ProviderCallExecutor(
        new ProviderCallPolicy(REQUEST_TIMEOUT, MAX_ATTEMPTS, budget, () -> 0.0),
        Clock.systemUTC(),
        waiter,
        TestMetrics.providerCalls());
  }
}
