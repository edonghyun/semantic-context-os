package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

// CollectionCycles의 JVM 전역 로거에 붙인 appender를 다른 세션과 공유하지 않는다.
@Isolated
@DisplayName("수집 복구 관찰은 예약 실행을 마친 뒤 반환한다(TESTING §7)")
class ClientCycleScenarioTest {
  @Test
  @DisplayName("복구 관찰이 반환되면 예약 스레드가 종료되어 있다")
  void stopsBeforeReturningRecovery(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCycleScenario(root)) {
      // when
      scenario.awaitRecovery();
      // then
      assertThat(scenario.runnerTerminated()).isTrue();
    }
  }
}
