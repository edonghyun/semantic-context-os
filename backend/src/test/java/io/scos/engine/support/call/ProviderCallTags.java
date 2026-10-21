package io.scos.engine.support.call;

import io.micrometer.core.instrument.Tags;
import io.scos.engine.support.call.ProviderCallMetrics.CallOutcome;
import io.scos.spi.connector.CallFailure;
import java.util.Optional;

/**
 * `scos.provider.calls` 카운터의 태그(테스트 공용): 커넥터의 공급자 호출 지표를 {@code
 * assertThatMetrics(registry).hasCounter(ProviderCallMetrics.PROVIDER_CALLS,
 * ProviderCallTags.of(operation, CallOutcome.RETRY, Optional.of(Kind.RATE_LIMITED)), n)}으로 단언한다. 태그
 * 값은 제품과 같은 변환으로 만든다. wire 형식 자체("retry", "dependency_unavailable", 없으면 "none")는 {@code
 * ProviderCallExecutorTest}가 문자열 판으로 고정한다.
 */
public final class ProviderCallTags {
  private ProviderCallTags() {}

  public static Tags of(
      String operation, CallOutcome outcome, Optional<CallFailure.Kind> failureKind) {
    return of(operation, outcome.wire(), ProviderCallMetrics.failureKindTag(failureKind));
  }

  /** wire 문자열 그대로 — 형식을 고정하는 테스트용. */
  public static Tags of(String operation, String outcome, String failureKind) {
    return Tags.of("operation", operation, "outcome", outcome, "failure_kind", failureKind);
  }
}
