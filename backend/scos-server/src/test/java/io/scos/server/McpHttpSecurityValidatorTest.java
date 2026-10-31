package io.scos.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SDK HTTP 보안 검증기의 빈 목록과 헤더 비교를 특성화한다")
class McpHttpSecurityValidatorTest {
  private static final String HOST = "mcp.example.com:8081";

  @Test
  @DisplayName("빈 Host 목록은 임의 Host를 통과시킨다")
  void emptyHostsAllowAnyHost() {
    var validator = DefaultServerTransportSecurityValidator.builder().build();

    // when·then
    assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of(HOST))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Origin이 없으면 허용 Host만으로 통과한다")
  void noOriginIsAccepted() {
    var validator = DefaultServerTransportSecurityValidator.builder().allowedHost(HOST).build();

    // when·then
    assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of(HOST))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("포트 없는 허용 Host는 포트가 붙은 요청과 일치하지 않는다")
  void hostPortMustMatch() {
    var validator =
        DefaultServerTransportSecurityValidator.builder().allowedHost("mcp.example.com").build();

    // when·then
    assertThatThrownBy(() -> validator.validateHeaders(Map.of("Host", List.of(HOST))))
        .isInstanceOf(ServerTransportSecurityException.class);
  }
}
