package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("관리 컨트롤러는 실제 관리 포트의 결과를 HTTP로 전달한다")
class AdminControllerTest {
  private final AdminHttpScenario scenario;

  AdminControllerTest() throws Exception {
    scenario = new AdminHttpScenario(false);
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("사용자를 생성하면 201과 정규화한 계정 정보를 반환한다")
  void controllerReturnsCreatedUser() throws Exception {
    var reply =
        scenario.admin(
            "POST", "/admin/v1/users", "{\"email\":\" Alice@Example.com \",\"role\":\"MEMBER\"}");

    // then
    assertThat(reply.status()).isEqualTo(201);
    assertThat(reply.json().path("email").asString()).isEqualTo("alice@example.com");
    assertThat(reply.json().path("role").asString()).isEqualTo("MEMBER");
  }

  @Test
  @DisplayName("비활성화 HTTP 요청은 다음 사용자 조회에 반영된다")
  void activationRequestChangesAccount() throws Exception {
    var reply = scenario.admin("PUT", "/admin/v1/users/member/active", "{\"active\":false}");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(scenario.userByEmail("member@example.com").path("active").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("재활성화 HTTP 요청은 사용자 상태를 되돌린다")
  void reactivationRequestChangesAccount() throws Exception {
    scenario.deactivateMember();
    var reply = scenario.admin("PUT", "/admin/v1/users/member/active", "{\"active\":true}");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(scenario.userByEmail("member@example.com").path("active").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("역할 변경 HTTP 요청은 관리 조회에 반영된다")
  void roleRequestChangesAccount() throws Exception {
    var reply = scenario.admin("PUT", "/admin/v1/users/member/role", "{\"role\":\"ADMIN\"}");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(scenario.userByEmail("member@example.com").path("role").asString())
        .isEqualTo("ADMIN");
  }

  @Test
  @DisplayName("토큰 폐기 HTTP 요청은 다음 인증에서 거부된다")
  void tokenDeletionRevokesAuthentication() throws Exception {
    var issued =
        scenario.admin("POST", "/admin/v1/users/member/tokens", "{\"label\":\"personal\"}");
    var id = issued.json().path("tokenId").asString();

    var reply = scenario.admin("DELETE", "/admin/v1/tokens/" + id, "");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(
            scenario
                .request(
                    "GET",
                    "/admin/v1/users/member/tokens",
                    "",
                    issued.json().path("value").asString(),
                    scenario.loopbackHost(),
                    null)
                .status())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("신원 연결 HTTP 요청은 해당 사용자 연결로 조회된다")
  void identityPutPersistsLink() throws Exception {
    var reply =
        scenario.admin(
            "PUT", "/admin/v1/identities/notion-a/reader@example.com", "{\"userId\":\"member\"}");

    // then
    assertThat(reply.status()).isEqualTo(204);
    var listed = scenario.admin("GET", "/admin/v1/identities?userId=member", "");
    assertThat(listed.json().path("items").get(0).path("sourceUserId").asString())
        .isEqualTo("reader@example.com");
  }

  @Test
  @DisplayName("신원 연결 해제 HTTP 요청은 목록에서 사라진다")
  void identityDeletionRemovesLink() throws Exception {
    scenario.admin(
        "PUT", "/admin/v1/identities/notion-a/reader@example.com", "{\"userId\":\"member\"}");

    var reply = scenario.admin("DELETE", "/admin/v1/identities/notion-a/reader@example.com", "");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(scenario.admin("GET", "/admin/v1/identities", "").json().path("items").size())
        .isZero();
  }

  @Test
  @DisplayName("Notion 전원 지정 HTTP 응답은 공개 범위 경고를 포함한다")
  void scopePutIncludesWarning() throws Exception {
    var reply = scenario.admin("PUT", "/admin/v1/scopes/notion-a", "");

    // then
    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.json().path("visibilityWarning").asString())
        .isEqualTo("이 소스의 모든 페이지가 전원에게 보입니다. 원천에서 제한한 하위 페이지도 포함됩니다.");
    assertThat(scenario.admin("GET", "/admin/v1/scopes", "").json().size()).isEqualTo(1);
  }

  @Test
  @DisplayName("전원 지정 해제 HTTP 요청은 목록에서 사라진다")
  void scopeDeletionRemovesDesignation() throws Exception {
    scenario.admin("PUT", "/admin/v1/scopes/notion-a", "");

    var reply = scenario.admin("DELETE", "/admin/v1/scopes/notion-a", "");

    // then
    assertThat(reply.status()).isEqualTo(204);
    assertThat(scenario.admin("GET", "/admin/v1/scopes", "").json().size()).isZero();
  }

  @Test
  @DisplayName("감사 조회 HTTP 요청은 선택한 action만 반환한다")
  void auditQueryFiltersActions() throws Exception {
    var reply = scenario.admin("GET", "/admin/v1/audit?action=token.issue&limit=1", "");

    // then
    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.json().path("items").size()).isEqualTo(1);
    assertThat(reply.json().path("items").get(0).path("action").asString())
        .isEqualTo("token.issue");
  }

  @Test
  @DisplayName("관리자는 원천 접근 진단을 조회한다")
  void administratorReadsSourceAccess() throws Exception {
    var reply = scenario.admin("GET", "/admin/v1/sources/access", "");

    // then
    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.json().size()).isEqualTo(1);
    assertThat(reply.json().get(0).path("kind").asString()).isEqualTo("google-drive");
  }

  @Test
  @DisplayName("소유 문서의 접근 설명은 요청한 사용자 진단을 반환한다")
  void knownDocumentReturnsAccessExplanation() throws Exception {
    // when
    var reply =
        scenario.admin("GET", "/admin/v1/sources/access/explain?documentId=doc&userId=reader", "");
    // then
    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.json().path("documentId").asString()).isEqualTo("doc");
    assertThat(reply.json().path("user").path("id").asString()).isEqualTo("reader");
  }

  @Test
  @DisplayName("미소유 문서의 접근 설명은 404로 반환한다")
  void unknownDocumentIsNotFound() throws Exception {
    var reply =
        scenario.admin(
            "GET", "/admin/v1/sources/access/explain?documentId=missing&userId=member", "");

    // then
    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.body()).isEqualTo("{\"error\":\"not_found\"}");
  }
}
