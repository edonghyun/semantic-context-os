package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("관리 오류 응답은 고정 코드만 전달한다")
class AdminHttpErrorsTest {
  private final AdminHttpScenario scenario;

  AdminHttpErrorsTest() throws Exception {
    scenario = new AdminHttpScenario(false);
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("역할 거부 오류는 원문 메시지 없이 403으로 반환한다")
  void httpErrorsMapForbiddenWithoutExceptionMessage() throws Exception {
    var reply = scenario.member("GET", "/admin/v1/users", "");

    // then
    assertThat(reply.status()).isEqualTo(403);
    assertThat(reply.body()).isEqualTo("{\"error\":\"forbidden\"}");
  }

  @Test
  @DisplayName("잘못된 페이지 크기는 400으로 반환한다")
  void invalidLimitIsBadRequest() throws Exception {
    var reply = scenario.admin("GET", "/admin/v1/users?limit=201", "");

    // then
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
  }

  @Test
  @DisplayName("잘못된 커서는 400으로 반환한다")
  void invalidCursorIsBadRequest() throws Exception {
    var reply = scenario.admin("GET", "/admin/v1/users?after=invalid", "");

    // then
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
  }

  @Test
  @DisplayName("중복 사용자 생성은 409로 반환한다")
  void duplicateUserIsConflict() throws Exception {
    var reply =
        scenario.admin(
            "POST", "/admin/v1/users", "{\"email\":\"member@example.com\",\"role\":\"MEMBER\"}");

    // then
    assertThat(reply.status()).isEqualTo(409);
    assertThat(reply.body()).isEqualTo("{\"error\":\"conflict\"}");
  }

  @Test
  @DisplayName("없는 사용자의 변경은 404로 반환한다")
  void unknownUserIsNotFound() throws Exception {
    var reply = scenario.admin("PUT", "/admin/v1/users/unknown/active", "{\"active\":false}");

    // then
    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.body()).isEqualTo("{\"error\":\"not_found\"}");
  }

  @Test
  @DisplayName("잘못된 JSON은 입력을 반사하지 않고 400으로 반환한다")
  void malformedJsonIsBadRequest() throws Exception {
    var reply = scenario.admin("POST", "/admin/v1/users", AdminHttpScenario.SENTINEL);

    // then
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
  }

  @Test
  @DisplayName("필수 active 값 누락은 비활성화하지 않고 거부한다")
  void absentActiveIsBadRequest() throws Exception {
    var reply = scenario.admin("PUT", "/admin/v1/users/member/active", "{}");

    // then
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
  }

  @Test
  @DisplayName("잘못된 토큰 식별자는 입력을 반사하지 않고 400으로 반환한다")
  void invalidTokenIdIsBadRequest() throws Exception {
    var reply = scenario.admin("DELETE", "/admin/v1/tokens/" + AdminHttpScenario.SENTINEL, "");

    // then
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).isEqualTo("{\"error\":\"invalid_request\"}");
  }
}
