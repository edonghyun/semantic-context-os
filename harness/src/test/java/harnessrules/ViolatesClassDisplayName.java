package harnessrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 위반 표본: 테스트를 가진 최상위 클래스에 @DisplayName이 없다. */
abstract class ViolatesClassDisplayName {
  @Test
  @DisplayName("제목이 있다")
  void titled() {
    // then
    assertThat(1).isPositive();
  }
}
