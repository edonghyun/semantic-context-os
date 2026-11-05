package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("저장된 ACL 원인은 새 버전의 값도 안전하게 읽는다")
class AccessFailureReasonTest {
  @ParameterizedTest
  @CsvSource({
    "NONE,none",
    "EXPIRED,expired",
    "ACL_UNREADABLE,acl_unreadable",
    "VISIBILITY_UNVERIFIED,visibility_unverified"
  })
  @DisplayName("알려진 원인은 저장값과 공개 표현을 보존한다")
  void knownReasonIsPreserved(String stored, String wire) {
    // when·then
    assertThat(AccessFailureReason.fromStored(stored).wire()).isEqualTo(wire);
  }

  @Test
  @DisplayName("모르는 원인은 UNKNOWN이다")
  void futureReasonIsUnknown() {
    // when·then
    assertThat(AccessFailureReason.fromStored("FUTURE_POLICY"))
        .isEqualTo(AccessFailureReason.UNKNOWN);
  }
}
