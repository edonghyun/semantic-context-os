package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.server.AdminMultiUserJourneyScenario.Account;
import io.scos.server.AdminMultiUserJourneyScenario.Mode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("db")
@Tag("process")
@Timeout(240)
@DisplayName("bootstrap과 실제 관리 API로 준비한 사용자는 문서와 토큰 경계를 지킨다")
class AdminMultiUserJourneyTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path directory;

  private AdminMultiUserJourneyScenario scenario;
  private Account alice;
  private Account bob;

  @BeforeEach
  void setUp() throws Exception {
    scenario = new AdminMultiUserJourneyScenario(directory);
  }

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  private void givenTwoUsers() throws Exception {
    scenario.bootstrap();
    alice = scenario.createMember("alice@example.com");
    bob = scenario.createMember("bob@example.com");
    scenario.link(alice, "U01");
    scenario.link(bob, "U02");
  }

  @Test
  @DisplayName("single-user bootstrap 토큰으로 로컬 관리 사용자 목록을 읽는다")
  void bootstrapPreparesLocalAdministration() throws Exception {
    // given
    scenario.bootstrap(Mode.SINGLE_USER);
    // when
    var reply = scenario.request("GET", "/users", Map.of());
    // then
    assertThat(reply.status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(200);
    assertThat(JSON.readTree(reply.body()).path("items").get(0).path("email").asString())
        .as("자식 로그 디렉터리: %s", directory)
        .isEqualTo("admin@example.com");
  }

  @Test
  @DisplayName("각 사용자는 본인 전용 문서와 공유 문서만 검색한다")
  void privateDocumentNeverAppearsForOtherUser() throws Exception {
    // given
    givenTwoUsers();
    // when
    var aliceDocuments =
        scenario.request(alice, "search_context", Map.of("query", "정산")).documents();
    var bobDocuments = scenario.request(bob, "search_context", Map.of("query", "정산")).documents();
    // then
    assertThat(aliceDocuments)
        .as("자식 로그 디렉터리: %s", directory)
        .containsExactlyInAnyOrder("alice-guide", "shared-guide");
    assertThat(bobDocuments)
        .as("자식 로그 디렉터리: %s", directory)
        .containsExactlyInAnyOrder("bob-guide", "shared-guide");
  }

  @Test
  @DisplayName("타인의 문서 읽기 오류는 부재 오류와 같은 고정 응답이다")
  void otherUsersDocumentReadMatchesMissingDocument() throws Exception {
    // given
    givenTwoUsers();
    var owner = scenario.request(bob, "read_document", Map.of("documentId", "bob-guide"));
    if (owner.content().path("units").isEmpty())
      throw new IllegalStateException("given 준비 실패: 소유자의 문서가 존재해야 한다");
    // when
    var blocked = scenario.request(alice, "read_document", Map.of("documentId", "bob-guide"));
    var missing = scenario.request(alice, "read_document", Map.of("documentId", "missing-guide"));
    // then
    // 요청마다 새로 만드는 상관 ID만 정규화한다. 고정 오류와 HTTP 외피는 그대로 비교한다.
    assertThat(blocked.withoutReference())
        .as("자식 로그 디렉터리: %s", directory)
        .isEqualTo(missing.withoutReference());
    assertThat(blocked.result().path("isError").asBoolean())
        .as("자식 로그 디렉터리: %s", directory)
        .isTrue();
    assertThat(blocked.result().path("content").get(0).path("text").asString())
        .as("자식 로그 디렉터리: %s", directory)
        .matches("요청을 처리할 수 없습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @Test
  @DisplayName("타인의 관련 문서 오류는 부재 오류와 같은 고정 응답이다")
  void otherUsersRelatedDocumentMatchesMissingDocument() throws Exception {
    // given
    givenTwoUsers();
    var owner = scenario.request(bob, "related_documents", Map.of("documentId", "bob-guide"));
    owner.content();
    // when
    var blocked = scenario.request(alice, "related_documents", Map.of("documentId", "bob-guide"));
    var missing =
        scenario.request(alice, "related_documents", Map.of("documentId", "missing-guide"));
    // then
    assertThat(blocked.withoutReference())
        .as("자식 로그 디렉터리: %s", directory)
        .isEqualTo(missing.withoutReference());
    assertThat(blocked.result().path("isError").asBoolean())
        .as("자식 로그 디렉터리: %s", directory)
        .isTrue();
    assertThat(blocked.result().path("content").get(0).path("text").asString())
        .as("자식 로그 디렉터리: %s", directory)
        .matches("요청을 처리할 수 없습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @Test
  @DisplayName("관리자가 비활성화하면 기존 토큰의 다음 MCP 요청은 401이다")
  void deactivationByAdminRejectsNextMcpRequest() throws Exception {
    // given
    givenTwoUsers();
    var before = scenario.request(alice, "search_context", Map.of("query", "정산"));
    before.requireDocument("alice-guide");
    // when
    var changed =
        scenario.request("PUT", "/users/" + alice.id() + "/active", Map.of("active", false));
    var after = scenario.request(alice, "search_context", Map.of("query", "정산"));
    // then
    assertThat(changed.status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(204);
    assertThat(after.http().status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(401);
  }

  @Test
  @DisplayName("재활성화해도 폐기된 기존 토큰의 다음 요청은 401이다")
  void reactivationStillRejectsOldToken() throws Exception {
    // given
    givenTwoUsers();
    scenario.request("PUT", "/users/" + alice.id() + "/active", Map.of("active", false));
    // when
    var changed =
        scenario.request("PUT", "/users/" + alice.id() + "/active", Map.of("active", true));
    var reply = scenario.request(alice, "search_context", Map.of("query", "정산"));
    // then
    assertThat(changed.status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(204);
    assertThat(reply.http().status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(401);
    assertThat(reply.http().body())
        .as("자식 로그 디렉터리: %s", directory)
        .isEqualTo("{\"error\":\"unauthorized\"}");
  }

  @Test
  @DisplayName("재활성화 뒤 새로 발급한 토큰은 MCP 문서를 다시 읽는다")
  void newTokenAfterReactivationWorks() throws Exception {
    // given
    givenTwoUsers();
    scenario.request("PUT", "/users/" + alice.id() + "/active", Map.of("active", false));
    scenario.request("PUT", "/users/" + alice.id() + "/active", Map.of("active", true));
    // when
    var issued =
        scenario.request("POST", "/users/" + alice.id() + "/tokens", Map.of("label", "renewed"));
    var renewed =
        new Account(
            alice.id(), AdminMultiUserJourneyScenario.issuedToken(JSON.readTree(issued.body())));
    var reply = scenario.request(renewed, "search_context", Map.of("query", "정산"));
    // then
    assertThat(issued.status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(201);
    assertThat(reply.http().status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(200);
    assertThat(reply.documents())
        .as("자식 로그 디렉터리: %s", directory)
        .containsExactlyInAnyOrder("alice-guide", "shared-guide");
  }

  @Test
  @DisplayName("Notion 조직 지정을 해제하면 다음 요청에서 문서가 숨겨진다")
  void scopeRevocationHidesNotionOnNextRequest() throws Exception {
    // given
    givenTwoUsers();
    scenario.designate();
    var before = scenario.request(alice, "search_context", Map.of("query", "정산"));
    before.requireDocument("notion-guide");
    // when
    var changed = scenario.request("DELETE", "/scopes/notion-a", Map.of());
    var after = scenario.request(alice, "search_context", Map.of("query", "정산"));
    // then
    assertThat(changed.status()).as("자식 로그 디렉터리: %s", directory).isEqualTo(204);
    assertThat(after.documents())
        .as("자식 로그 디렉터리: %s", directory)
        .containsExactlyInAnyOrder("alice-guide", "shared-guide");
  }

  @Test
  @DisplayName("확정 이메일이 바뀐 run 마감 뒤 이전 사용자의 자동 접근은 회수된다")
  void autoEmailInvalidationRemovesOldUserAccessAfterRun() throws Exception {
    // given
    givenTwoUsers();
    scenario.collect("alice@example.com");
    var before = scenario.request(alice, "search_context", Map.of("query", "장애"));
    before.requireDocument("C0000000001/day-2025-10-14");
    // when
    scenario.collect("changed@example.com");
    var after = scenario.request(alice, "search_context", Map.of("query", "장애"));
    var retained = scenario.request(bob, "search_context", Map.of("query", "장애"));
    // then
    assertThat(after.documents()).as("자식 로그 디렉터리: %s", directory).isEmpty();
    assertThat(retained.documents())
        .as("자식 로그 디렉터리: %s", directory)
        .contains("C0000000001/day-2025-10-14");
  }
}
