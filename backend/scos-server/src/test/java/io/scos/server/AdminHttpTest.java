package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

@Isolated
@DisplayName("관리 REST는 사용자 토큰과 현재 역할로 보호한다")
class AdminHttpTest extends AdminHttpContract {}

/** 메모리와 실제 HTTP·PG가 같은 관리 경계 계약을 실행한다. */
@Isolated
@DisplayName("관리 HTTP 구현은 같은 인증·역할·비밀 보호 계약을 지킨다")
abstract class AdminHttpContract {
  private AdminHttpScenario scenario;

  AdminHttpScenario start(boolean multi) throws Exception {
    scenario = newScenario(multi);
    return scenario;
  }

  AdminHttpScenario newScenario(boolean multi) throws Exception {
    return new AdminHttpScenario(multi);
  }

  @AfterEach
  void close() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("구성원이 사용자를 생성하면 거부하고 저장 상태를 보존한다")
  void memberCannotCreateUserOverHttp() throws Exception {
    var s = start(false);
    var reply =
        s.member(
            "POST", "/admin/v1/users", "{\"email\":\"alice@example.com\",\"role\":\"MEMBER\"}");

    // then
    assertThat(reply.status()).isEqualTo(403);
    assertThat(reply.body()).isEqualTo("{\"error\":\"forbidden\"}");
    assertThat(s.userEmails()).containsExactly("admin@example.com", "member@example.com");
  }

  @Test
  @DisplayName("구성원이 본인 토큰을 발급하면 새 토큰으로 인증한다")
  void memberCanIssueOwnTokenOverHttp() throws Exception {
    var s = start(false);
    var issued = s.member("POST", "/admin/v1/users/member/tokens", "{\"label\":\"personal\"}");

    // then
    assertThat(issued.status()).isEqualTo(201);
    var reply =
        s.request(
            "GET",
            "/admin/v1/users/member/tokens",
            "",
            issued.json().path("value").asString(),
            s.loopbackHost(),
            null);
    assertThat(reply.status()).isEqualTo(200);
  }

  @Test
  @DisplayName("단일 사용자 관리에는 공유 토큰 대신 사용자 토큰이 필요하다")
  void singleUserAdminRequiresUserToken() throws Exception {
    var s = start(false);
    var shared =
        s.request("GET", "/admin/v1/users", "", AdminHttpScenario.SHARED, s.loopbackHost(), null);

    // then
    assertThat(shared.status()).isEqualTo(401);
    assertThat(s.admin("GET", "/admin/v1/users", "").status()).isEqualTo(200);
  }

  @Test
  @DisplayName("principal 없는 요청은 관리 서비스에 도달하지 않는다")
  void missingPrincipalCannotReachAdminService() throws Exception {
    var s = start(false);
    var reply = s.request("GET", "/admin/v1/users", "", null, s.loopbackHost(), null);

    // then
    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    assertThat(reply.challenge()).isEqualTo("Bearer");
  }

  @Test
  @DisplayName("비활성화 뒤 다음 관리 요청은 동일한 401로 거부한다")
  void deactivatedUserIsUnauthorizedOnNextAdminRequest() throws Exception {
    var s = start(false);
    s.deactivateMember();
    var reply = s.member("GET", "/admin/v1/users/member/tokens", "");

    // then
    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    assertThat(reply.challenge()).isEqualTo("Bearer");
  }

  @Test
  @DisplayName("토큰 폐기 뒤 다음 관리 요청은 동일한 401로 거부한다")
  void revokedTokenIsUnauthorizedOnNextAdminRequest() throws Exception {
    var s = start(false);
    s.revokeMemberToken();
    var reply = s.member("GET", "/admin/v1/users/member/tokens", "");

    // then
    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    assertThat(reply.challenge()).isEqualTo("Bearer");
  }

  @Test
  @DisplayName("단일 사용자 관리에서 비루프백 Host는 거부한다")
  void singleUserAdminRejectsNonLoopbackHost() throws Exception {
    var s = start(false);

    // then
    assertThat(s.host(s.foreignHostOnAppPort(), null).status()).isEqualTo(403);
  }

  @Test
  @DisplayName("단일 사용자 관리에서 외부 Origin은 거부한다")
  void singleUserAdminRejectsForeignOrigin() throws Exception {
    var s = start(false);

    // then
    assertThat(s.host(s.loopbackHost(), "https://evil.example.com").status()).isEqualTo(403);
  }

  @Test
  @DisplayName("다중 사용자 관리에서도 허용 목록 밖 Host를 거부한다")
  void adminRoutesUseSameHostPolicyAsMcp() throws Exception {
    var s = start(true);

    // then
    assertThat(s.host(s.foreignHostOnAppPort(), null).status()).isEqualTo(403);
  }

  @Test
  @DisplayName("다중 사용자 관리에서도 허용 목록 밖 Origin을 거부한다")
  void multiUserAdminRejectsForeignOrigin() throws Exception {
    var s = start(true);

    // then
    assertThat(s.host(s.configuredHost(), "https://evil.example.com").status()).isEqualTo(403);
  }

  @Test
  @DisplayName("단일 사용자에서는 설정에 있는 비루프백 Host도 거부한다")
  void singleUserRejectsConfiguredNonLoopbackHost() throws Exception {
    var s = start(false);
    // when
    var reply = s.host(s.configuredHost(), null);
    // then
    assertThat(reply.status()).isEqualTo(403);
  }

  @Test
  @DisplayName("단일 사용자에서는 설정에 있는 비루프백 Origin도 거부한다")
  void singleUserRejectsConfiguredNonLoopbackOrigin() throws Exception {
    var s = start(false);
    // when
    var reply = s.host(s.loopbackHost(), "https://client.example.com");
    // then
    assertThat(reply.status()).isEqualTo(403);
  }

  @Test
  @DisplayName("다중 사용자에서는 설정에 있는 Host와 Origin을 허용한다")
  void multiUserAllowsConfiguredHostAndOrigin() throws Exception {
    var s = start(true);
    // when
    var reply = s.host(s.configuredHost(), "https://client.example.com");
    // then
    assertThat(reply.status()).isEqualTo(200);
  }

  @Test
  @DisplayName("다중 사용자에서는 설정에 없는 루프백 Host를 거부한다")
  void multiUserRejectsUnlistedLoopbackHost() throws Exception {
    var s = start(true);
    // when
    var reply = s.host(s.loopbackHost(), null);
    // then
    assertThat(reply.status()).isEqualTo(403);
  }

  @Test
  @DisplayName("단일 사용자 관리 API는 Actuator 포트에 없다")
  void adminApiIsAbsentOnActuatorPort() throws Exception {
    var s = start(false);

    // then
    assertThat(s.managementRequest().status()).isEqualTo(404);
  }

  @Test
  @DisplayName("다중 사용자 관리 API는 Actuator 포트에 없다")
  void multiUserAdminApiIsAbsentOnActuatorPort() throws Exception {
    var s = start(true);

    // then
    assertThat(s.managementRequest().status()).isEqualTo(404);
  }

  @Test
  @DisplayName("다중 사용자 앱 포트에는 Actuator가 없다")
  void actuatorIsAbsentOnApplicationPort() throws Exception {
    var s = start(true);

    // then
    assertThat(s.overHttp("GET", "/actuator/health", "", s.adminToken()).status()).isEqualTo(404);
  }

  @Test
  @DisplayName("구성원이 원천 상태를 조회하면 거부한다")
  void memberCannotReadSourceStatusOrAuditOverHttp() throws Exception {
    var s = start(false);

    // then
    assertThat(s.member("GET", "/admin/v1/sources/access", "").status()).isEqualTo(403);
  }

  @Test
  @DisplayName("구성원이 감사를 조회하면 거부한다")
  void memberCannotReadAuditOverHttp() throws Exception {
    var s = start(false);

    // then
    assertThat(s.member("GET", "/admin/v1/audit", "").status()).isEqualTo(403);
  }

  @Test
  @DisplayName("발급 비밀은 생성 응답에만 있고 후속 목록에는 없다")
  void issuedSecretAppearsOnlyInCreationResponse() throws Exception {
    var s = start(false);
    var issued = s.admin("POST", "/admin/v1/users/member/tokens", "{\"label\":\"new\"}");

    // then
    assertThat(issued.status()).isEqualTo(201);
    String value = issued.json().path("value").asString();
    var listed = s.admin("GET", "/admin/v1/users/member/tokens", "");
    assertThat(listed.status()).isEqualTo(200);
    assertThat(listed.body()).contains("tokenId").doesNotContain(value, "token_hash", "value");
  }

  @Test
  @DisplayName("발급 비밀은 로그와 감사에 남지 않는다")
  void tokenValueIsAbsentFromLogsAndAudit() throws Exception {
    var s = start(false);
    try (var logs = s.debugLogs()) {
      var issued = s.admin("POST", "/admin/v1/users/member/tokens", "{\"label\":\"new\"}");

      // then
      assertThat(issued.status()).isEqualTo(201);
      String value = issued.json().path("value").asString();
      assertThat(logs.events()).isNotBlank().doesNotContain(value.substring(0, 12));
      assertThat(s.auditEvents().toString()).doesNotContain(value.substring(0, 12));
    }
  }

  @Test
  @DisplayName("form 본문은 서버 장애 로그 없이 고정 400으로 거부한다")
  void unsupportedContentTypeIsBadRequest() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(AdminHttpErrors.class)) {
      var reply =
          s.adminWithContentType(
              "PUT",
              "/admin/v1/users/member/active",
              "{\"active\":false}",
              "application/x-www-form-urlencoded");

      // then
      assertThat(reply.status()).isEqualTo(400);
      assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
      assertThat(logs.events("admin.http.failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("미지원 메서드는 서버 장애 로그 없이 고정 400으로 거부한다")
  void unsupportedMethodIsBadRequest() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(AdminHttpErrors.class)) {
      var reply = s.admin("PATCH", "/admin/v1/users", "");

      // then
      assertThat(reply.status()).isEqualTo(400);
      assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
      assertThat(logs.events("admin.http.failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("미지원 응답 형식은 서버 장애 로그 없이 고정 400으로 거부한다")
  void unacceptableMediaTypeIsBadRequest() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(AdminHttpErrors.class)) {
      var reply = s.adminAccepting("application/xml");

      // then
      assertThat(reply.status()).isEqualTo(400);
      assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
      assertThat(logs.events("admin.http.failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("관리 내부 실패 응답에는 고정 코드와 참조만 있다")
  void internalAdminFailureHasFixedBody() throws Exception {
    var s = start(false);
    s.failStorage();
    var reply = s.admin("GET", "/admin/v1/users", "");
    s.restoreStorage();

    // then
    assertThat(reply.status()).isEqualTo(500);
    assertThat(reply.body()).matches("\\{\"error\":\"internal\",\"ref\":\"[a-f0-9]{12}\"}");
  }

  @Test
  @DisplayName("관리 실패 로그에 예외 메시지의 비밀은 남지 않는다")
  void exceptionMessageCannotLeakIssuedTokenToLog() throws Exception {
    var s = start(false);
    s.failStorage();
    try (var logs = LogCapture.of(AdminHttpErrors.class)) {
      var reply = s.admin("GET", "/admin/v1/users", "");
      s.restoreStorage();

      // then
      assertThat(reply.status()).isEqualTo(500);
      assertThat(logs.events().toString())
          .contains("admin.http.failed")
          .doesNotContain(AdminHttpScenario.SENTINEL);
    }
  }

  @Test
  @DisplayName("관리 응답에는 저장 금지 캐시 헤더를 붙인다")
  void adminResponsesAreNotCacheable() throws Exception {
    var s = start(false);

    // then
    assertThat(s.admin("GET", "/admin/v1/users", "").cache()).isEqualTo("no-store");
  }

  @Test
  @DisplayName("프레임워크 DEBUG 로그에도 관리 예외 메시지는 남지 않는다")
  void frameworkDebugLoggingCannotLeakExceptionMessage() throws Exception {
    var s = start(false);
    s.failFatally();
    try (var debug = s.debugExceptionResolver();
        var logs = LogCapture.of(ExceptionHandlerExceptionResolver.class)) {
      var reply = s.admin("GET", "/admin/v1/users", "");
      s.restoreStorage();

      // then
      assertThat(reply.status()).isEqualTo(500);
      assertThat(logs.events().toString()).doesNotContain(AdminHttpScenario.SENTINEL);
    }
  }

  @Test
  @DisplayName("관리 인증 장애의 로그에는 예외 메시지의 비밀이 없다")
  void adminAuthenticationFailureCannotLeakSecret() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(PrincipalBearerFilter.class);
        var adminLogs = LogCapture.of(AdminHttpErrors.class)) {
      var reply = s.consumeForAuthenticationFailure(false);

      // then
      assertThat(reply.status()).isEqualTo(500);
      assertThat(List.of(logs.events().toString(), adminLogs.events().toString()))
          .noneMatch(event -> event.contains(AdminHttpScenario.SENTINEL));
      assertThat(adminLogs.events("admin.http.failed"))
          .singleElement()
          .extracting(
              event -> event.path("detail").path("ref").asString().length(),
              event -> event.path("detail").path("reason").asString(),
              event -> event.path("detail").path("errorType").asString(),
              event -> event.has("error"))
          .containsExactly(12, "internal", "IllegalStateException", false);
    }
  }

  @Test
  @DisplayName("관리 인증 Error는 원문을 다시 던지지 않고 500을 반환한다")
  void adminAuthenticationErrorCannotLeakSecret() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(PrincipalBearerFilter.class)) {
      var reply = s.consumeForAuthenticationFailure(true);

      // then
      assertThat(reply.status()).isEqualTo(500);
      assertThat(reply.body()).doesNotContain(AdminHttpScenario.SENTINEL);
      assertThat(logs.events().toString()).doesNotContain(AdminHttpScenario.SENTINEL);
    }
  }

  @Test
  @DisplayName("인코딩된 관리 경로도 인증 장애의 원문 메시지를 감춘다")
  void encodedAdminAuthenticationFailureCannotLeakSecret() throws Exception {
    var s = start(false);
    try (var logs = LogCapture.of(PrincipalBearerFilter.class)) {
      var reply = s.consumeForAuthenticationFailure(false, "/%61dmin/v1/users");

      // then
      assertThat(reply.status()).isEqualTo(500);
      assertThat(logs.events().toString()).doesNotContain(AdminHttpScenario.SENTINEL);
    }
  }

  @Test
  @DisplayName("관리 JSON 요청의 비밀은 컨버터 DEBUG 로그에 남지 않는다")
  void requestBodyCannotLeakSecretToFrameworkDebugLog() throws Exception {
    var s = start(false);
    try (var debug = s.debugBodyReads();
        var logs = LogCapture.of(RequestResponseBodyMethodProcessor.class)) {
      var reply =
          s.admin(
              "POST",
              "/admin/v1/users",
              "{\"email\":\"" + AdminHttpScenario.SENTINEL + "\",\"role\":\"MEMBER\"}");

      // then
      assertThat(reply.status()).isEqualTo(400);
      assertThat(logs.events().toString()).doesNotContain(AdminHttpScenario.SENTINEL);
    }
  }
}
