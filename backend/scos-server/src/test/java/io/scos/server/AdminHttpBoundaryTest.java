package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.admin.AdminPort;
import io.scos.support.LogCapture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.handler.SimpleMappingExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.JsonNode;

@DisplayName("관리 HTTP의 마지막 경계는 이미 쓴 원문도 고정 오류로 바꾼다(T-c16)")
@Isolated
class AdminHttpBoundaryTest {
  private final Scenario scenario = new Scenario();

  @Test
  @DisplayName("필터 오류는 앞서 쓴 원문을 지우고 고정 500 응답을 남긴다")
  void filterReplacesPartialBody() throws Exception {
    // when
    var reply = scenario.failAfterPartialWrite();
    // then
    assertThat(reply.status()).isEqualTo(500);
    assertThat(reply.json().path("error").asString()).isEqualTo("internal");
    assertThat(reply.body()).doesNotContain("synthetic-private");
  }

  @Test
  @DisplayName("필터가 만든 오류 본문은 JSON으로 표시한다")
  void filterLabelsJson() throws Exception {
    // when
    var reply = scenario.failAfterPartialWrite();
    // then
    assertThat(reply.contentType()).isEqualTo("application/json");
  }

  @Test
  @DisplayName("필터 실패 로그에는 원문 대신 응답과 같은 참조가 남는다")
  void filterLogsSafeReference() throws Exception {
    try (var logs = LogCapture.of(AdminHttpErrors.class)) {
      // when
      var reply = scenario.failAfterPartialWrite();
      // then
      assertThat(logs.events("admin.http.failed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("ref").asString())
                      .isEqualTo(reply.json().path("ref").asString()));
      assertThat(logs.events().toString()).doesNotContain("synthetic-private");
    }
  }

  @Test
  @DisplayName("현재 사용자 인증 거부는 Bearer 도전을 보존한다")
  void resolverPreservesAuthenticationChallenge() throws Exception {
    // when
    var reply = scenario.resolve(new AdminPort.Failure("unauthorized"));
    // then
    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.challenge()).isEqualTo("Bearer");
    assertThat(reply.json().path("error").asString()).isEqualTo("unauthorized");
  }

  @Test
  @DisplayName("resolver가 만든 오류 본문은 JSON으로 표시한다")
  void resolverLabelsJson() throws Exception {
    // when
    var reply = scenario.resolve(new IllegalArgumentException("synthetic-private"));
    // then
    assertThat(reply.contentType()).isEqualTo("application/json");
    assertThat(reply.json().path("error").asString()).isEqualTo("invalid_request");
  }

  @Test
  @DisplayName("프레임워크 resolver가 먼저 등록돼도 관리 오류 경계가 우선한다")
  void boundaryRunsBeforeFrameworkResolver() throws Exception {
    // when
    var reply = scenario.resolveAheadOfFramework();
    // then
    assertThat(reply.status()).isEqualTo(500);
    assertThat(reply.json().path("error").asString()).isEqualTo("internal");
    assertThat(reply.body()).doesNotContain("synthetic-private");
  }

  @Test
  @DisplayName("알 수 없는 관리 실패 코드는 원문을 숨긴 내부 오류로 닫는다")
  void unknownFailureCodeIsInternal() throws Exception {
    // when
    var reply = scenario.resolve(new AdminPort.Failure("synthetic-private"));
    // then
    assertThat(reply.status()).isEqualTo(500);
    assertThat(reply.json().path("error").asString()).isEqualTo("internal");
    assertThat(reply.body()).doesNotContain("synthetic-private");
  }

  @ParameterizedTest
  @MethodSource("missingRoutes")
  @DisplayName("매핑과 리소스 부재는 원문 없는 404로 변환한다")
  void missingRouteIsNotFound(Exception failure) throws Exception {
    // when
    var reply = scenario.resolve(failure);
    // then
    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.body()).isEqualTo("{\"error\":\"not_found\"}");
  }

  static Stream<Exception> missingRoutes() {
    return Stream.of(
        new NoHandlerFoundException("GET", "/synthetic-private", HttpHeaders.EMPTY),
        new NoResourceFoundException(HttpMethod.GET, "synthetic-private", "synthetic-private"));
  }

  @Test
  @DisplayName("관리 경계를 지나지 않은 요청은 다른 resolver에 맡긴다")
  void unrelatedRequestIsNotHandled() {
    // when
    boolean handled = scenario.resolveUnrelated();
    // then
    assertThat(handled).isFalse();
  }

  private record Reply(int status, String contentType, String challenge, String body) {
    JsonNode json() {
      return QueryScenario.JSON.readTree(body);
    }
  }

  private static final class Scenario {
    private final AdminHttpErrors boundary = new AdminHttpErrors(QueryScenario.JSON);

    Reply failAfterPartialWrite() throws Exception {
      var request = new MockHttpServletRequest();
      var response = new MockHttpServletResponse();
      boundary.doFilter(
          request,
          response,
          (incoming, outgoing) -> {
            outgoing.getOutputStream().write("synthetic-private".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("synthetic-private");
          });
      return reply(response);
    }

    Reply resolve(Exception failure) throws Exception {
      var request = new MockHttpServletRequest();
      var response = new MockHttpServletResponse();
      boundary.doFilter(request, response, (incoming, outgoing) -> {});
      boundary.resolveException(request, response, null, failure);
      return reply(response);
    }

    boolean resolveUnrelated() {
      return boundary.resolveException(
              new MockHttpServletRequest(),
              new MockHttpServletResponse(),
              null,
              new IllegalArgumentException("synthetic-private"))
          != null;
    }

    Reply resolveAheadOfFramework() throws Exception {
      var fallback = new SimpleMappingExceptionResolver();
      fallback.setDefaultErrorView("framework-error");
      var framework = new HandlerExceptionResolverComposite();
      framework.setOrder(0);
      framework.setExceptionResolvers(List.of(fallback));
      List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(framework, boundary));
      AnnotationAwareOrderComparator.sort(resolvers);
      var chain = new HandlerExceptionResolverComposite();
      chain.setExceptionResolvers(resolvers);
      var request = new MockHttpServletRequest();
      var response = new MockHttpServletResponse();
      boundary.doFilter(request, response, (incoming, outgoing) -> {});
      chain.resolveException(
          request, response, null, new IllegalStateException("synthetic-private"));
      return reply(response);
    }

    private Reply reply(MockHttpServletResponse response) throws Exception {
      return new Reply(
          response.getStatus(),
          response.getContentType(),
          response.getHeader("WWW-Authenticate"),
          response.getContentAsString());
    }
  }
}
