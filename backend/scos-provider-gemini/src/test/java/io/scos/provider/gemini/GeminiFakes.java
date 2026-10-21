package io.scos.provider.gemini;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.support.FakeHttpServer;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;

/** Gemini 어댑터 테스트(임베더·생성기)가 함께 쓰는 가짜 서버 시작과 결정적 실행기(지터 0, 기본 대기는 취소만 본다). */
final class GeminiFakes {
  static final int MAX_ATTEMPTS = 3;
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  static final Duration BUDGET = Duration.ofMinutes(2);

  private GeminiFakes() {}

  static FakeHttpServer startHttp() {
    try {
      return new FakeHttpServer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ProviderCallExecutor executor() {
    return executor((d, c) -> c.isCancelled(), BUDGET);
  }

  static ProviderCallExecutor executor(ProviderCallExecutor.Waiter waiter, Duration budget) {
    return new ProviderCallExecutor(
        new ProviderCallPolicy(REQUEST_TIMEOUT, MAX_ATTEMPTS, budget, () -> 0.0),
        Clock.systemUTC(),
        waiter,
        TestMetrics.providerCalls());
  }
}
