package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("state 열기 실패는 수집 잠금을 반환하고 재획득을 허용한다(CG-6)")
class ClientAcquisitionTest {
  @Test
  @DisplayName("state를 열지 못하면 그 주기가 잡은 잠금을 즉시 놓는다")
  void releasesLockWhenStateOpenFails(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientAcquisitionScenario(root)) {
      // when
      Throwable failure = catchThrowable(scenario::collect);
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("state.sqlite schema 999 is newer than 3");
      assertThat(scenario.lockAvailable()).isTrue();
    }
  }

  @Test
  @DisplayName("state 열기 실패 원인을 고치면 다음 주기가 잠금을 다시 잡고 수집한다")
  void reacquiresAfterStateRepair(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientAcquisitionScenario(root)) {
      scenario.givenFailedStateOpen();
      scenario.repairState();
      // when
      var report = scenario.collect();
      // then
      assertThat(report.documentsProjected()).isEqualTo(2);
    }
  }
}
