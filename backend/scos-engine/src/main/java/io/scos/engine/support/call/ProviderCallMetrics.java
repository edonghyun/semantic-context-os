package io.scos.engine.support.call;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.scos.spi.connector.CallFailure;
import java.util.Locale;
import java.util.Optional;

/**
 * 공급자 호출 지표 `scos.provider.calls`(ADR-0009 이름 고정, ADR-0022 §4): 수집 어댑터와 공급자 어댑터(Gemini)가 같은 실행기를 거쳐
 * 같은 지표를 남긴다. 태그는 저카디널리티만 — operation·outcome·failure_kind.
 */
public final class ProviderCallMetrics {
  public static final String PROVIDER_CALLS = "scos.provider.calls";

  /** 공급자 호출 한 시도의 결과: 성공, 재시도 예정, 최종 실패, 취소. */
  public enum CallOutcome {
    OK,
    RETRY,
    FAILED,
    CANCELLED;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final MeterRegistry registry;

  private ProviderCallMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public static ProviderCallMetrics of(MeterRegistry registry) {
    return new ProviderCallMetrics(registry);
  }

  /** failureKind가 없으면(성공, 실패 전 취소) failure_kind=none. */
  public void providerCall(
      String operation, CallOutcome outcome, Optional<CallFailure.Kind> failureKind) {
    Counter.builder(PROVIDER_CALLS)
        .tag("operation", operation)
        .tag("outcome", outcome.wire())
        .tag("failure_kind", failureKindTag(failureKind))
        .register(registry)
        .increment();
  }

  static String failureKindTag(Optional<CallFailure.Kind> failureKind) {
    return failureKind.map(k -> k.name().toLowerCase(Locale.ROOT)).orElse("none");
  }
}
