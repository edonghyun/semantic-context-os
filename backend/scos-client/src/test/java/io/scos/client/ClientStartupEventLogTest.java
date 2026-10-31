package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("클라이언트는 기동 실패와 저하 수집 실패를 운영 이벤트로 남긴다(LG-3·LG-6)")
// 같은 제품 로거를 쓰는 다른 테스트의 이벤트가 섞이지 않게 한다.
@Isolated
class ClientStartupEventLogTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @DisplayName("색인 열기 재시도는 시도 횟수를 warn으로 남긴다")
  void retryIsWarn(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientStartupScenario(root)) {
      scenario.givenHomeIsFile();

      // when
      scenario.run();

      // then
      assertThat(scenario.events("client.index.open_retry"))
          .containsExactly(
              JSON.readTree(
                  """
          {"level":"warn","event":"client.index.open_retry",
           "detail":{"operation":"client.index.open","attempt":1,"maxAttempts":3,
                     "errorType":"UncheckedIOException"}}
          """),
              JSON.readTree(
                  """
          {"level":"warn","event":"client.index.open_retry",
           "detail":{"operation":"client.index.open","attempt":2,"maxAttempts":3,
                     "errorType":"UncheckedIOException"}}
          """),
              JSON.readTree(
                  """
          {"level":"warn","event":"client.index.open_retry",
           "detail":{"operation":"client.index.open","attempt":3,"maxAttempts":3,
                     "errorType":"UncheckedIOException"}}
          """));
    }
  }

  @Test
  @DisplayName("색인을 끝내 열지 못하면 error 이벤트를 남긴다")
  void exhaustedOpenIsError(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientStartupScenario(root)) {
      scenario.givenHomeIsFile();

      // when
      scenario.run();

      // then
      assertThat(scenario.events("client.index.unavailable"))
          .containsExactly(
              JSON.readTree(
                  """
          {"level":"error","event":"client.index.unavailable",
           "detail":{"operation":"client.index.open"}}
          """));
    }
  }

  @Test
  @DisplayName("state만 수집하는 저하 경로도 실패하면 error를 남긴다")
  void stateFailureIsError(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientStartupScenario(root)) {
      scenario.givenHomeIsFile();

      // when
      scenario.run();

      // then
      assertThat(scenario.events("client.state.failed"))
          .containsExactly(
              JSON.readTree(
                  """
          {"level":"error","event":"client.state.failed",
           "detail":{"operation":"client.state.collect","errorType":"UncheckedIOException"}}
          """));
    }
  }

  @Test
  @DisplayName("수집 주기 실패는 다음 주기를 이어갈 warn 이벤트로 남긴다")
  void cycleFailureIsWarn(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientStartupScenario(root)) {
      scenario.givenUnreadableState();

      // when
      scenario.runCycle();

      // then
      assertThat(scenario.events("client.cycle.failed"))
          .containsExactly(
              JSON.readTree(
                  """
          {"level":"warn","event":"client.cycle.failed",
           "detail":{"operation":"client.collection.cycle","errorType":"IllegalStateException"}}
          """));
    }
  }
}
