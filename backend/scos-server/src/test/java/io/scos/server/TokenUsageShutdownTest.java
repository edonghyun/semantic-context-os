package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@DisplayName("토큰 사용 기록기는 종료 반영 실패도 운영 이벤트로 보고한다")
@Isolated
class TokenUsageShutdownTest {
  @Test
  @DisplayName("종료 반영 실패는 원인과 함께 오류 이벤트 하나를 남긴다")
  void reportsShutdownFailure() {
    // given
    try (var scenario = new TokenUsageScenario();
        var logs = LogCapture.of(TokenUsageRecorder.class)) {
      scenario.record(TokenUsageScenario.TOKEN, TokenUsageScenario.FIRST);
      scenario.givenWriteFailure();

      // when
      var unused = catchThrowable(scenario::close);

      // then
      assertThat(logs.events("http.token_usage.failed"))
          .singleElement()
          .extracting(e -> e.path("level").asString(), e -> e.at("/error/message").asString())
          .containsExactly("error", "usage-write-fixture");
    }
  }

  @Test
  @DisplayName("종료 반영 실패는 원래 예외를 전파한다")
  void propagatesShutdownFailure() {
    // given
    try (var scenario = new TokenUsageScenario()) {
      scenario.record(TokenUsageScenario.TOKEN, TokenUsageScenario.FIRST);
      scenario.givenWriteFailure();

      // when
      Throwable failure = catchThrowable(scenario::close);

      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("usage-write-fixture");
    }
  }
}
