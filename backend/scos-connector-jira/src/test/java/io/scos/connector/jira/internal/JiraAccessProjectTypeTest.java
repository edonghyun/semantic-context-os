package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.connector.jira.internal.JiraAccessDirectory.ProjectStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 프로젝트 유형과 제품은 공개 관측 근거로만 분리한다 (T-b16, TM 설계 §6.1)")
class JiraAccessProjectTypeTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @Test
  @DisplayName("TM은 CM 스킴과 보안 수준 API를 호출하지 않는다")
  void teamManagedDoesNotReadPermissionScheme() {
    // given
    jira.answer("/project/P1", teamManaged());
    // when
    jira.readProject("P1");
    // then
    assertThat(jira.requestsTo("/project/P1/permissionscheme")).isZero();
    assertThat(jira.requestsTo("/issuesecurityschemes/project")).isZero();
  }

  @Test
  @DisplayName("TM 프로젝트는 미개방 정책 차단으로 아무도 허용하지 않는다")
  void teamManagedProjectGrantsNobody() {
    // given
    jira.answer("/project/P1", teamManaged());
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.POLICY_BLOCKED);
    assertThat(project.projectGrant()).isEmpty();
    assertThat(project.levels()).isEmpty();
  }

  @Test
  @DisplayName("접근 수준 필드가 없는 TM 응답을 open으로 추정하지 않는다")
  void projectResponseWithoutAccessLevelIsNotOpen() {
    // given
    jira.answer("/project/P1", teamManaged());
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).isEmpty();
    assertThat(project.issueGrants()).isEmpty();
  }

  @Test
  @DisplayName("isPrivate=false는 open 접근을 증명하지 않는다")
  void isPrivateFalseDoesNotProveOpenAccess() {
    // given
    jira.answer("/project/P1", teamManaged().replace("}", ",\"isPrivate\":false}"));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.projectGrant()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"simplified\":false",
        "\"style\":\"future\",\"simplified\":false",
        "\"style\":\"classic\"",
        "\"style\":\"classic\",\"simplified\":\"false\"",
        "\"style\":\"classic\",\"simplified\":true",
        "\"style\":\"next-gen\",\"simplified\":false",
        "\"style\":3,\"simplified\":false",
        "\"style\":null,\"simplified\":false",
        "\"style\":\"classic\",\"simplified\":null"
      })
  @DisplayName("유형 필드 누락·형식 오류·상충은 관측 실패로 차단한다")
  void unknownProjectStyleGrantsNobody(String fields) {
    // given
    jira.answer("/project/P1", "{\"id\":\"P1\",\"projectTypeKey\":\"software\"," + fields + "}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.style()).isEqualTo(ProjectStyle.UNKNOWN);
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.projectGrant()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"service_desk", "futureProduct"})
  @DisplayName("명시적 비지원 제품은 정책으로 차단한다")
  void unsupportedProductDoesNotReadScheme(String product) {
    // given
    jira.answer(
        "/project/P1",
        "{\"id\":\"P1\",\"projectTypeKey\":\""
            + product
            + "\",\"style\":\"classic\",\"simplified\":false}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.POLICY_BLOCKED);
    assertThat(jira.requestsTo("/project/P1/permissionscheme")).isZero();
  }

  private String teamManaged() {
    return "{\"id\":\"P1\",\"key\":\"P1\",\"projectTypeKey\":\"software\",\"style\":\"next-gen\",\"simplified\":true}";
  }

  @Test
  @DisplayName("next-gen과 simplified=true는 TM으로 식별한다")
  void teamManagedProjectStyleIsRecognized() {
    // given
    jira.answer("/project/P1", teamManaged());
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.style()).isEqualTo(ProjectStyle.TEAM_MANAGED);
  }
}
