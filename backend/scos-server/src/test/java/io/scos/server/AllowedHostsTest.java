package io.scos.server;

import static io.scos.server.AllowedHostsScenario.HOST;
import static io.scos.server.AllowedHostsScenario.ORIGIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("허용 Host 필터는 비허용 요청을 고정 JSON으로 거부한다")
class AllowedHostsTest {
  @Test
  @DisplayName("Host 목록이 비면 모든 요청을 거부한다")
  void emptyHostsRefuseRequests() throws Exception {
    var scenario = new AllowedHostsScenario(List.of(), List.of());

    var reply = scenario.request(HOST, null);

    // then
    assertThat(List.of(reply.response().getStatus(), reply.response().getContentType()))
        .containsExactly(403, "application/json");
    assertThat(reply.response().getContentAsByteArray())
        .isEqualTo("{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(reply.reachedChain()).isFalse();
  }

  @Test
  @DisplayName("Origin 없는 요청은 목록에 있는 Host이면 통과한다")
  void allowedHostPassesWithoutOrigin() throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of());

    var reply = scenario.request(HOST, null);

    // then
    assertThat(reply.reachedChain()).isTrue();
    assertThat(reply.response().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("목록에 없는 Origin은 허용 Host에서도 거부한다")
  void unlistedOriginIsForbidden() throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of(ORIGIN));

    var reply = scenario.request(HOST, "https://unlisted.example.com");

    // then
    assertThat(reply.response().getStatus()).isEqualTo(403);
    assertThat(reply.reachedChain()).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "mcp.example.com",
        "mcp.example.com:8082",
        "other.example.com:8081",
        "mcp.example.com:8081.evil.example.com"
      })
  @DisplayName("Host가 없거나 포트까지 정확히 일치하지 않으면 거부한다")
  void unlistedHostIsForbidden(String host) throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of(ORIGIN));

    var reply = scenario.request(host, null);

    // then
    assertThat(reply.response().getStatus()).isEqualTo(403);
    assertThat(reply.reachedChain()).isFalse();
  }

  @Test
  @DisplayName("Host와 Origin이 모두 목록에 있으면 통과한다")
  void listedOriginPasses() throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of(ORIGIN));

    var reply = scenario.request(HOST, ORIGIN);

    // then
    assertThat(reply.reachedChain()).isTrue();
  }

  @Test
  @DisplayName("Origin 목록이 비면 Origin이 있는 요청을 거부한다")
  void emptyOriginsRefusePresentOrigin() throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of());

    var reply = scenario.request(HOST, ORIGIN);

    // then
    assertThat(reply.response().getStatus()).isEqualTo(403);
    assertThat(reply.reachedChain()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Host", "Origin"})
  @DisplayName("Host나 Origin이 여러 개이면 허용 값이 먼저 와도 거부한다")
  void ambiguousHeadersAreForbidden(String header) throws Exception {
    var scenario = new AllowedHostsScenario(List.of(HOST), List.of(ORIGIN));

    var reply = scenario.duplicateHeader(header);

    // then
    assertThat(reply.response().getStatus()).isEqualTo(403);
    assertThat(reply.reachedChain()).isFalse();
  }
}
