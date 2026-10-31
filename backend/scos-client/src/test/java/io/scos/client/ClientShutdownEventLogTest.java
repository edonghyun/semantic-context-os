package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientShutdownScenario.Result;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("process")
@DisplayName("수집 종료 상한은 실제 stdio 프로세스 수명에서 운영 오류로 남는다(LG-3·LG-6)")
class ClientShutdownEventLogTest {
  @Test
  @DisplayName("취소 뒤에도 수집이 멈추지 않으면 상한과 함께 error를 남긴다")
  void timeoutIsError(@TempDir Path root) throws Exception {
    // given
    var scenario = new ClientShutdownScenario(root);

    // when
    Result result = scenario.run();

    // then
    assertThat(result)
        .extracting(Result::exitCode, Result::events)
        .containsExactly(
            0,
            JsonMapper.builder()
                .build()
                .readTree(
                    """
            [{"level":"error","event":"client.collection.shutdown_timed_out",
              "detail":{"operation":"client.collection.shutdown","waitMillis":10000}}]
            """));
  }

  @Test
  @DisplayName("종료 상한이 지나면 stdio는 반환하고 진행 중인 수집의 잠금은 닫지 않는다")
  void timeoutReturnsWithoutClosingCollection(@TempDir Path root) throws Exception {
    // given
    var scenario = new ClientShutdownScenario(root);
    // when
    Result result = scenario.run();
    // then
    assertThat(result)
        .extracting(Result::exitCode, Result::clientExitCode, Result::lockHeld)
        .containsExactly(0, 0, true);
  }
}
