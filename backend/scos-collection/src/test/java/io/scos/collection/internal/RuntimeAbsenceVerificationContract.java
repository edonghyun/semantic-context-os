package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("런타임은 전체 부재 검증의 요구와 결과를 독립적으로 확인한다")
public abstract class RuntimeAbsenceVerificationContract {
  protected abstract RuntimeAbsenceVerificationScenario newContext();

  @Test
  @DisplayName("검증을 선언한 어댑터가 결과 없이 끝나면 런타임은 전체 정리를 보류한다")
  void missingAdapterVerificationDefersCleanup() {
    var context = newContext();
    var report = context.collect();
    // then
    assertThat(report.errorKind()).contains(RunCompletion.ABSENCE_DEFERRED);
  }

  @Test
  @DisplayName("현재 후보의 검증 결과를 페이지로 커밋하면 런타임은 전체 정리를 허용한다")
  void committedAdapterVerificationAllowsCleanup() {
    var context = newContext();
    context.verifyOutsideCandidate();
    var report = context.collect();
    // then
    assertThat(report.deleted()).isEqualTo(1);
    assertThat(report.errorKind()).isEmpty();
  }
}
