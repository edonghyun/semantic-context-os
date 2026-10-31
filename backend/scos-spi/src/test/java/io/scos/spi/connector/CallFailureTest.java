package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 분류된 공급자 실패: 응답 검사 위반은 잘못된 공급자 응답에만 붙는다(다른 종류에 붙으면 분류가 틀린 것이다). */
@DisplayName("공급자 실패의 응답 검사 위반은 invalid_provider_response에만 붙는다")
class CallFailureTest {
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CallFailure.Kind.class,
      names = "INVALID_PROVIDER_RESPONSE",
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("다른 종류에 위반을 붙이면 만들기를 거부한다")
  void rejectsViolationOnOtherKinds(CallFailure.Kind kind) {
    // when·then
    assertThatThrownBy(
            () ->
                new CallFailure(
                    kind,
                    "m",
                    Optional.empty(),
                    Optional.of(CallFailure.Violation.of("results_not_array"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
