package io.scos.engine.support.call;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.call.ProviderCallMetrics.CallOutcome;
import io.scos.spi.connector.CallFailure.Kind;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * `scos.provider.calls` 태그의 wire 문자열 표(F15 T15.1, tr-search-misc High 4): 커넥터 테스트는
 * ProviderCallTags로 제품과 같은 변환을 쓰므로, 변환 자체를 여기서 손으로 쓴 문자열로 고정한다(TS 지표 이름과 같다). 사례는 enum 전체를 돌므로 새 값이
 * 생기면 손 표에 wire 이름을 더하기 전까지 실패한다(표 완결성).
 */
@DisplayName("공급자 호출 지표 태그는 정해진 wire 문자열이다")
class ProviderCallWireTest {
  static final Map<Kind, String> FAILURE_KIND_WIRE =
      Map.ofEntries(
          entry(Kind.INVALID_CONFIGURATION, "invalid_configuration"),
          entry(Kind.INVALID_CHECKPOINT, "invalid_checkpoint"),
          entry(Kind.AUTHENTICATION_FAILED, "authentication_failed"),
          entry(Kind.PERMISSION_DENIED, "permission_denied"),
          entry(Kind.RATE_LIMITED, "rate_limited"),
          entry(Kind.DEPENDENCY_UNAVAILABLE, "dependency_unavailable"),
          entry(Kind.INVALID_PROVIDER_RESPONSE, "invalid_provider_response"),
          entry(Kind.PAYLOAD_TOO_LARGE, "payload_too_large"),
          entry(Kind.STORAGE_FAILED, "storage_failed"),
          entry(Kind.CHECKPOINT_CONFLICT, "checkpoint_conflict"),
          entry(Kind.CANCELLED, "cancelled"));

  static final Map<CallOutcome, String> OUTCOME_WIRE =
      Map.of(
          CallOutcome.OK, "ok",
          CallOutcome.RETRY, "retry",
          CallOutcome.FAILED, "failed",
          CallOutcome.CANCELLED, "cancelled");

  static <E extends Enum<E>> String handWire(Map<E, String> table, E value) {
    String wire = table.get(value);
    if (wire == null)
      throw new IllegalStateException(
          "given 준비 실패: 손 wire 표에 " + value + "가 없다 — 새 값의 wire 이름을 표에 더한다");
    return wire;
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Kind.class)
  @DisplayName("failure_kind는 실패 종류의 wire 이름이다")
  void failureKindTagIsWireName(Kind kind) {
    // given
    String wire = handWire(FAILURE_KIND_WIRE, kind);

    // when
    String tag = ProviderCallMetrics.failureKindTag(Optional.of(kind));

    // then
    assertThat(tag).isEqualTo(wire);
  }

  @Test
  @DisplayName("실패 종류가 없으면 failure_kind는 none이다")
  void noFailureIsNone() {
    // when
    String tag = ProviderCallMetrics.failureKindTag(Optional.empty());

    // then
    assertThat(tag).isEqualTo("none");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CallOutcome.class)
  @DisplayName("outcome은 시도 결과의 wire 이름이다")
  void outcomeIsWireName(CallOutcome outcome) {
    // given
    String wire = handWire(OUTCOME_WIRE, outcome);

    // when
    String tag = outcome.wire();

    // then
    assertThat(tag).isEqualTo(wire);
  }
}
