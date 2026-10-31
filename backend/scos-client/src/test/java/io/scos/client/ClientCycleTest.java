package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

// CollectionCycles의 JVM 전역 로거에 붙인 appender가 다른 세션의 로그를 받지 않게 한다.
@Isolated
@DisplayName("수집 주기 실패는 이후의 예약 실행을 중단하지 않는다(CG-6)")
class ClientCycleTest {
  @Test
  @DisplayName("첫 주기가 실패해도 다음 예약 주기가 문서를 수집한다")
  void recoversOnNextScheduledCycle(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCycleScenario(root)) {
      // when
      scenario.awaitRecovery();
      // then
      assertThat(scenario.failures()).hasSize(1);
      assertThat(scenario.sessionIds()).containsExactly("transcript/d/s1");
    }
  }
}
