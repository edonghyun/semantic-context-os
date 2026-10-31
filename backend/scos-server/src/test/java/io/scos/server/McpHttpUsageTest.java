package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.AccessProperties.Mode;
import io.scos.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@DisplayName("HTTP 토큰 사용 기록은 조립된 스케줄러에서 실패를 보고한다")
@Isolated
class McpHttpUsageTest {
  @Test
  @DisplayName("주기 반영 실패는 원인과 함께 오류 이벤트로 남긴다")
  void scheduledFailureIsReported() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER);
        var logs = LogCapture.of(TokenUsageRecorder.class)) {
      http.givenUsageWriteFailure();

      http.flushScheduledUsage();

      // then
      assertThat(logs.events("http.token_usage.failed"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.path("level").asString()).isEqualTo("error");
                assertThat(event.path("error").path("message").asString())
                    .isEqualTo("http-usage-write-fixture");
              });
    }
  }

  @Test
  @DisplayName("주기 반영 실패는 Spring 오류 로거에 중복 기록하지 않는다")
  void scheduledFailureHasNoDuplicateLog() throws Exception {
    // given
    try (var http = new McpHttpStartup(Mode.MULTI_USER);
        var logs = http.schedulerErrors()) {
      http.givenUsageWriteFailure();

      // when
      http.flushScheduledUsage();

      // then
      assertThat(logs.events()).isEmpty();
    }
  }

  @Test
  @DisplayName("주기 실패로 남은 배치는 컨텍스트 종료 때 다시 반영한다")
  void shutdownRetriesFailedBatch() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      http.givenUsageWriteFailure();

      http.flushScheduledUsage();

      // then
      assertThat(http.lastUsed()).contains(PrincipalBearerScenario.NOW);
    }
  }

  @Test
  @DisplayName("인증된 사용은 컨텍스트 종료 때 반영한다")
  void shutdownFlushesAuthenticatedUse() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      http.authorize(AllowedHostsScenario.HOST, "Bearer " + PrincipalBearerScenario.TOKEN);

      // then
      assertThat(http.lastUsed()).contains(PrincipalBearerScenario.NOW);
    }
  }

  @Test
  @DisplayName("주기 반영이 실패해도 다음 주기는 종료 전에 저장을 재시도한다")
  void nextTickRetriesBeforeShutdown() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      http.givenUsageWriteFailure();

      var persisted = http.retryUsageOnNextTick();

      // then
      assertThat(persisted).contains(PrincipalBearerScenario.NOW);
    }
  }
}
