package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("SDK stateless 서버는 핸들러 실패 메시지를 JSON-RPC 오류로 직렬화한다")
class McpSdkFailureTest {
  @Test
  @DisplayName("RuntimeException은 자기 메시지만 싣고 cause 문구는 싣지 않는다")
  void runtimeMessageReachesWire() throws Exception {
    try (var scenario =
        new McpSdkFailureScenario(
            new IllegalStateException("secret-doc", new RuntimeException("secret-cause")))) {
      // when
      JsonNode wire = scenario.call();

      // then
      assertThat(wire.path("error").path("message").asString()).isEqualTo("secret-doc");
      assertThat(wire.toString()).doesNotContain("secret-cause");
    }
  }

  @Test
  @DisplayName("Error도 자기 메시지만 싣고 cause 문구는 싣지 않는다")
  void errorMessageReachesWire() throws Exception {
    try (var scenario =
        new McpSdkFailureScenario(new Error("secret-doc", new RuntimeException("secret-cause")))) {
      // when
      JsonNode wire = scenario.call();

      // then
      assertThat(wire.path("error").path("message").asString()).isEqualTo("secret-doc");
      assertThat(wire.toString()).doesNotContain("secret-cause");
    }
  }
}
