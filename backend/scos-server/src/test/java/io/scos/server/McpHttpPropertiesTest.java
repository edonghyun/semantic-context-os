package io.scos.server;

import static io.scos.server.McpHttpConfiguration.MIN_TOKEN_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.server.McpHttpConfiguration.McpHttpProperties;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("HTTP MCP 설정은 쓸 수 없는 값을 거부하고, 문자열에 토큰을 싣지 않는다")
class McpHttpPropertiesTest {
  static final String MIN_LENGTH_TOKEN = "t".repeat(MIN_TOKEN_LENGTH);
  static final int READ_POOL_SIZE = 4;
  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
  static final String TOKEN_ENV = "SCOS_MCP_HTTP_TOKEN";
  static final String MASKED = "***";

  static McpHttpProperties enabledWith(String token) {
    var properties =
        new McpHttpProperties(
            true, token, READ_POOL_SIZE, CONNECTION_TIMEOUT, List.of(), List.of());
    properties.requireSharedToken();
    return properties;
  }

  @Test
  @DisplayName("최소 길이 토큰은 받는다")
  void minimumLengthTokenAccepted() {
    // then
    assertThat(enabledWith(MIN_LENGTH_TOKEN).token()).isEqualTo(MIN_LENGTH_TOKEN);
  }

  @Test
  @DisplayName("앞뒤 공백은 지운 뒤 토큰으로 쓴다")
  void surroundingWhitespaceIsStripped() {
    // then
    assertThat(enabledWith("  " + MIN_LENGTH_TOKEN + "\n").token()).isEqualTo(MIN_LENGTH_TOKEN);
  }

  static Stream<Arguments> unusableTokens() {
    String oneShort = "t".repeat(MIN_TOKEN_LENGTH - 1);
    return Stream.of(
        Arguments.of("없음", null),
        Arguments.of("최소 길이보다 한 자 짧음", oneShort),
        Arguments.of("공백을 지우면 짧음", "  " + oneShort + "  "),
        Arguments.of("가운데 공백", "t".repeat(MIN_TOKEN_LENGTH) + " " + "t".repeat(4)),
        Arguments.of("가운데 개행", "t".repeat(MIN_TOKEN_LENGTH) + "\n" + "t".repeat(4)),
        Arguments.of("제어 문자", "t".repeat(MIN_TOKEN_LENGTH) + "\u007f"),
        Arguments.of("줄바꿈 없는 공백(NBSP)", "t".repeat(MIN_TOKEN_LENGTH) + "\u00a0" + "t"),
        Arguments.of("ASCII가 아닌 문자", "t".repeat(MIN_TOKEN_LENGTH) + "토큰"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unusableTokens")
  @DisplayName("헤더로 보낼 수 없는 토큰이면 환경 변수를 짚어 거부한다")
  void unusableTokenRefused(String label, String token) {
    // then
    assertThatThrownBy(() -> enabledWith(token)).hasMessageContaining(TOKEN_ENV);
  }

  @Test
  @DisplayName("읽기 풀 크기가 0이면 거부한다")
  void emptyReadPoolRefused() {
    // then
    assertThatThrownBy(
            () ->
                new McpHttpProperties(
                    true, MIN_LENGTH_TOKEN, 0, CONNECTION_TIMEOUT, List.of(), List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("문자열로 바꾸면 토큰 자리에 가림 표시를 두고, 나머지 필드는 보인다")
  void toStringMasksToken() {
    // given
    McpHttpProperties props = enabledWith(MIN_LENGTH_TOKEN);

    // then
    assertThat(props.toString())
        .isEqualTo(
            "McpHttpProperties[enabled=true, token="
                + MASKED
                + ", readPoolSize="
                + READ_POOL_SIZE
                + ", connectionTimeout="
                + CONNECTION_TIMEOUT
                + "]");
  }
}
