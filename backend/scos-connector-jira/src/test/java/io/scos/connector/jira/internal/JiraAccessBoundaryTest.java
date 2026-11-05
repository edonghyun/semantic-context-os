package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Jira 완전 관측의 경계와 관측 전달을 유지한다 (T-b16, PIT 반례)")
class JiraAccessBoundaryTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @Test
  @DisplayName("두 프로젝트가 같은 그룹을 참조하면 완전한 관측을 재사용한다")
  void cachedGroupKeepsCompleteMembers() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 1, jira.userJson("U2")), "{}");
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.readProject("P1");
    // when
    var project = jira.readProject("P2");
    // then
    assertThat(project.browseUsers()).containsExactly("U2");
    assertThat(jira.requestsTo("/group/member")).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"reader@example.com,true", "external@elsewhere.com,false"})
  @DisplayName("신원 이메일 검증은 허용된 원천 도메인에만 부여한다")
  void identityEmailRequiresAllowedSourceDomain(String email, boolean verified) {
    // given
    jira.userResponse("U1", jira.userWithEmail("U1", email));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.observations().identities())
        .extracting(identity -> identity.emailVerified())
        .containsExactly(verified);
  }

  @Test
  @DisplayName("보안 수준의 내용 바인딩 snapshot도 sink에 전달한다")
  void levelGroupIsEmitted() {
    // given
    jira.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.security("P1");
    // when
    var groups = jira.observedGroups("P1");
    // then
    assertThat(groups)
        .containsExactlyInAnyOrder(
            "~project:P1:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6",
            "~level:L1:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("완료 확인도 취소를 즉시 전달한다")
  void cancelledDirectoryCannotComplete() {
    // given
    jira.readProject("P1");
    jira.cancel();
    // when
    var failure = catchThrowable(jira::requireComplete);
    // then
    assertThat(failure)
        .isInstanceOf(CallFailureException.class)
        .hasMessage("jira.access: cancelled — Jira access cancelled");
  }

  @Test
  @DisplayName("maxResults만큼 꽉 찬 페이지도 유효하다")
  void fullPageIsAccepted() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 1, jira.userJson("U2"))
            .replace("maxResults\":50", "maxResults\":1"),
        "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactly("U2");
  }

  @Test
  @DisplayName("다음 URL이 실제 offset과 일치하면 전체 페이지를 읽는다")
  void validNextPageIsAccepted() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    String first = JiraAccessScenario.page(0, 2, jira.userJson("U2"));
    jira.groupPages(
        first.substring(0, first.length() - 1)
            + ",\"nextPage\":\"https://example.atlassian.net/page?startAt=1\"}",
        JiraAccessScenario.page(1, 2, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactlyInAnyOrder("U2", "U3");
  }

  @Test
  @DisplayName("페이지 상한을 넘는 전체 집합은 확정하지 않는다")
  void groupPageLimitIsIncomplete() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.longGroup(1001);
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.failure().orElseThrow().message()).isEqualTo("Jira access page limit");
    assertThat(jira.requestsTo("/group/member")).isEqualTo(1000);
  }

  @Test
  @DisplayName("후속 페이지가 거부돼도 이미 관측한 신원 회수는 보존한다")
  void groupFailurePreservesObservedIdentityRestriction() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 2, jira.inactiveUser("U2")), "{}");
    jira.response(
        "/group/member?groupId=G1&includeInactiveUsers=true&startAt=1&maxResults=50", 403, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.observations().groups()).isEmpty();
    assertThat(project.observations().identities())
        .extracting(identity -> identity.deleted())
        .containsExactly(true);
  }
}
