package io.scos.server;

import static io.scos.server.LocalBearerFilterTest.Outcome.FORBIDDEN;
import static io.scos.server.LocalBearerFilterTest.Outcome.PASSED;
import static io.scos.server.LocalBearerFilterTest.Outcome.UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("HTTP MCP 앞문은 로컬 요청과 맞는 토큰만 다음 단계로 넘긴다")
class LocalBearerFilterTest {
  static final String VALID_TOKEN = "v".repeat(McpHttpConfiguration.MIN_TOKEN_LENGTH);
  static final String WRONG_TOKEN = "w".repeat(McpHttpConfiguration.MIN_TOKEN_LENGTH);
  static final String LOCAL_HOST = "127.0.0.1:8081";

  enum Outcome {
    PASSED,
    UNAUTHORIZED,
    FORBIDDEN
  }

  /** 요청 한 번을 필터에 보내고 결과를 업무 언어로 돌려준다. 통과 = 다음 단계에 실제로 도달. */
  static final class Request {
    private String host = LOCAL_HOST;
    private String origin;
    private String authorization = "Bearer " + VALID_TOKEN;
    private MockHttpServletResponse response;

    static Request local() {
      return new Request();
    }

    Request host(String host) {
      this.host = host;
      return this;
    }

    Request origin(String origin) {
      this.origin = origin;
      return this;
    }

    Request authorization(String authorization) {
      this.authorization = authorization;
      return this;
    }

    Outcome send() throws Exception {
      MockHttpServletRequest request =
          new MockHttpServletRequest("POST", McpHttpConfiguration.PATH);
      if (host != null) request.addHeader("Host", host);
      if (origin != null) request.addHeader("Origin", origin);
      if (authorization != null) request.addHeader("Authorization", authorization);
      response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();
      new LocalBearerFilter(VALID_TOKEN).doFilter(request, response, chain);
      if (chain.getRequest() != null) return PASSED;
      return switch (response.getStatus()) {
        case HttpServletResponse.SC_UNAUTHORIZED -> UNAUTHORIZED;
        case HttpServletResponse.SC_FORBIDDEN -> FORBIDDEN;
        default -> throw new AssertionError("통과도 401·403도 아닌 상태: " + response.getStatus());
      };
    }

    String header(String name) {
      return response.getHeader(name);
    }
  }

  @ParameterizedTest(name = "Host {0}")
  @ValueSource(
      strings = {"localhost", "127.0.0.1", "[::1]", "127.0.0.1:8081", "[::1]:8081", "LOCALHOST:1"})
  @DisplayName("로컬 Host와 맞는 토큰이면 다음 단계로 넘긴다")
  void localHostWithTokenPasses(String host) throws Exception {
    // then
    assertThat(Request.local().host(host).send()).isEqualTo(PASSED);
  }

  @ParameterizedTest(name = "Origin {0}")
  @ValueSource(strings = {"http://localhost:3000", "https://127.0.0.1", "http://[::1]:5173"})
  @DisplayName("로컬 Origin이면 다음 단계로 넘긴다")
  void localOriginPasses(String origin) throws Exception {
    // then
    assertThat(Request.local().origin(origin).send()).isEqualTo(PASSED);
  }

  static Stream<Arguments> badAuthorizations() {
    return Stream.of(
        Arguments.of("없음", null),
        Arguments.of("다른 토큰", "Bearer " + WRONG_TOKEN),
        Arguments.of("스킴 없는 토큰", VALID_TOKEN),
        Arguments.of("빈 Bearer", "Bearer "),
        Arguments.of("다른 스킴", "Basic " + VALID_TOKEN));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badAuthorizations")
  @DisplayName("토큰이 맞지 않으면 401")
  void badTokenIsUnauthorized(String label, String authorization) throws Exception {
    // then
    assertThat(Request.local().authorization(authorization).send()).isEqualTo(UNAUTHORIZED);
  }

  @Test
  @DisplayName("401은 Bearer 인증을 요구한다고 알린다")
  void unauthorizedAnnouncesBearer() throws Exception {
    // given
    Request request = Request.local().authorization(null);

    // when
    request.send();

    // then
    assertThat(request.header("WWW-Authenticate")).isEqualTo("Bearer");
  }

  static Stream<Arguments> foreignRequests() {
    return Stream.of(
        Arguments.of("외부 Host", "evil.example:8081", null),
        Arguments.of("Host 없음", null, null),
        Arguments.of("로컬처럼 보이는 Host", "localhost.evil.example:8081", null),
        Arguments.of("로컬 IP로 시작하는 Host", "127.0.0.1.evil.example", null),
        Arguments.of("대괄호 뒤에 붙인 Host", "[::1]evil.example", null),
        Arguments.of("빈 포트", "[::1]:", null),
        Arguments.of("숫자가 아닌 포트(대괄호)", "[::1]:abc", null),
        Arguments.of("숫자가 아닌 포트", "localhost:abc", null),
        Arguments.of("외부 Origin", LOCAL_HOST, "http://evil.example"),
        Arguments.of("로컬처럼 보이는 Origin", LOCAL_HOST, "http://localhost.evil.example"),
        Arguments.of("사용자 정보를 끼운 Origin", LOCAL_HOST, "http://localhost@evil.example"),
        Arguments.of("null Origin", LOCAL_HOST, "null"),
        Arguments.of("http(s)가 아닌 Origin", LOCAL_HOST, "file://x"),
        Arguments.of("깨진 Origin", LOCAL_HOST, "http://[bad"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("foreignRequests")
  @DisplayName("로컬이 아닌 요청은 토큰이 맞아도 403")
  void foreignIsForbidden(String label, String host, String origin) throws Exception {
    // then
    assertThat(Request.local().host(host).origin(origin).send()).isEqualTo(FORBIDDEN);
  }
}
