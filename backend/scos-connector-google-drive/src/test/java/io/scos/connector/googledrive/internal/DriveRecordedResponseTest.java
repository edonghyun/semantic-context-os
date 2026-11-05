package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.connector.RawAcl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Drive 어댑터는 생성 403과 실측 모양 표본을 같은 계약으로 읽는다")
class DriveRecordedResponseTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("독자 403의 code·message·domain은 확정 거부 분류를 바꾸지 않는다")
  void readerForbiddenClosesAcl(boolean recorded) {
    try (var drive = new DriveAccessScenario()) {
      drive.readerForbidden(recorded);
      // when·then
      assertThat(drive.collect("file:F1").acl("file:F1"))
          .isEqualTo(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, DriveAccessScenario.NOW));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("독자 403도 관측을 마친 COMPLETE다")
  void readerForbiddenCompletes(boolean recorded) {
    try (var drive = new DriveAccessScenario()) {
      drive.readerForbidden(recorded);
      // when·then
      assertThat(drive.collect("file:F1").failure()).isEmpty();
    }
  }
}
