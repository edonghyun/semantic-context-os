package io.scos.server;

import static io.scos.server.DaemonAccessSeed.ALICE_DOCUMENT;
import static io.scos.server.DaemonAccessSeed.BOB_DOCUMENT;
import static io.scos.server.DaemonAccessSeed.SHARED_DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.server.DaemonMultiUserHttpScenario.HttpReply;
import io.scos.server.DaemonMultiUserHttpScenario.SearchReply;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("db")
@Tag("process")
@Timeout(240)
@TestInstance(PER_CLASS)
@DisplayName("실제 다중 사용자 HTTP 데몬은 인증과 문서 접근 경계를 지킨다")
class DaemonMultiUserProcessTest {
  DaemonMultiUserHttpScenario scenario;

  @BeforeAll
  void start(@TempDir Path dir) throws Exception {
    scenario = new DaemonMultiUserHttpScenario(dir);
  }

  @AfterAll
  void close() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("두 사용자가 동시에 같은 질의를 보내도 허용 문서만 받는다")
  void simultaneousUsersSeeOnlyTheirDocuments() throws Exception {
    var documents = scenario.simultaneousSearches();

    // then
    assertThat(documents)
        .containsExactly(
            new SearchReply(HttpServletResponse.SC_OK, Set.of(ALICE_DOCUMENT, SHARED_DOCUMENT)),
            new SearchReply(HttpServletResponse.SC_OK, Set.of(BOB_DOCUMENT, SHARED_DOCUMENT)));
  }

  @Test
  @DisplayName("동시 네 요청에서도 각 사용자의 허용 문서 집합을 유지한다")
  void fourRequestsRetainUserBoundaries() throws Exception {
    var alice = new SearchReply(HttpServletResponse.SC_OK, Set.of(ALICE_DOCUMENT, SHARED_DOCUMENT));
    var bob = new SearchReply(HttpServletResponse.SC_OK, Set.of(BOB_DOCUMENT, SHARED_DOCUMENT));

    var documents = scenario.fourSimultaneousSearches();

    // then
    assertThat(documents).containsExactly(alice, bob, alice, bob);
  }

  @Test
  @DisplayName("토큰 없음·틀림·폐기·비활성은 상태·헤더·본문이 같은 401이다")
  void authenticationFailuresAreIdentical() {
    var replies = scenario.unauthorizedRequests();

    // then
    assertThat(replies).hasSize(4).containsOnly(replies.getFirst());
    assertThat(replies.getFirst())
        .extracting(HttpReply::status, HttpReply::challenge, HttpReply::body)
        .containsExactly(
            HttpServletResponse.SC_UNAUTHORIZED, List.of("Bearer"), "{\"error\":\"unauthorized\"}");
  }

  @Test
  @DisplayName("인증한 사용자를 비활성화하면 바로 다음 요청부터 401이다")
  void deactivationTakesEffectOnNextRequest() {
    var statuses = scenario.deactivateBetweenRequests();

    // then
    assertThat(statuses)
        .containsExactly(HttpServletResponse.SC_OK, HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  @DisplayName("다중 사용자 데몬의 Actuator는 MCP 포트에 없고 관리 포트에 있다")
  void actuatorIsOnlyOnManagementPort() {
    var statuses = scenario.actuatorStatuses();

    // then
    assertThat(statuses)
        .containsExactly(HttpServletResponse.SC_NOT_FOUND, HttpServletResponse.SC_OK);
  }

  @Test
  @DisplayName("허용 목록에 없는 Host는 유효한 사용자 토큰으로도 403이다")
  void foreignHostIsForbidden() {
    var reply = scenario.foreignHost();

    // then
    assertThat(reply.status()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  @DisplayName("인증 저장소 예외는 고정 오류와 참조 ID만 담은 500이다")
  void infrastructureFailureExposesOnlyReference() {
    var reply = scenario.authenticationStorageFailure();

    // then
    assertThat(reply.status()).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertThat(reply.body())
        .matches("\\{\"error\":\"internal\",\"ref\":\"[0-9a-f]{8}-[0-9a-f]{3}\"}");
  }

  @Test
  @DisplayName("HTTP를 연 같은 jar도 다중 사용자 stdio는 거부한다")
  void sameJarRefusesMultiUserStdio() throws Exception {
    var exit = scenario.refuseStdio();

    // then
    assertThat(exit.code()).isEqualTo(DaemonProcessScenario.STARTUP_FAILED_EXIT);
    assertThat(exit.output()).contains("stdio MCP를 열 수 없습니다. 데몬 HTTP MCP(사용자 토큰)를 쓰세요");
  }
}
