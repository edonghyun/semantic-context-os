package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira ACL은 정상 200의 누락·상충·잘린 전체 집합을 허용하지 않는다 (T-b16)")
class JiraAccessResponseTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @ParameterizedTest
  @MethodSource("invalidResponses")
  @DisplayName("필수 응답의 누락·상충은 관측 실패다")
  void malformedDependencyIsFailed(String path, String body) {
    // given
    jira.answer(path, body);
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.projectGrant()).isEmpty();
  }

  static Stream<Arguments> invalidResponses() {
    return Stream.of(
        Arguments.of(
            "/project/P1",
            "{\"id\":\"P2\",\"style\":\"classic\",\"simplified\":false,\"projectTypeKey\":\"software\"}"),
        Arguments.of("/project/P1", "{\"id\":\"P1\",\"style\":\"classic\",\"simplified\":false}"),
        Arguments.of(
            "/project/P1",
            "{\"id\":\"P1\",\"style\":\"classic\",\"simplified\":false,\"projectTypeKey\":3}"),
        Arguments.of(
            "/permissionscheme/SP1?expand=permissions", "{\"id\":\"other\",\"permissions\":[]}"),
        Arguments.of("/permissionscheme/SP1?expand=permissions", "{\"id\":\"SP1\"}"),
        Arguments.of(
            "/permissionscheme/SP1?expand=permissions", "{\"id\":\"SP1\",\"permissions\":[{}]}"),
        Arguments.of(
            "/permissionscheme/SP1?expand=permissions",
            "{\"id\":\"SP1\",\"permissions\":[{\"permission\":\"BROWSE_PROJECTS\"}]}"),
        Arguments.of(
            "/user?accountId=U1",
            "{\"accountId\":\"U2\",\"accountType\":\"atlassian\",\"active\":true}"),
        Arguments.of(
            "/user?accountId=U1",
            "{\"accountId\":\"U1\",\"accountType\":\"future\",\"active\":true}"),
        Arguments.of("/user?accountId=U1", "{\"accountId\":\"U1\",\"accountType\":\"atlassian\"}"),
        Arguments.of(
            "/user?accountId=U1",
            "{\"accountId\":\"U1\",\"accountType\":\"atlassian\",\"active\":\"true\"}"),
        Arguments.of(
            "/user?accountId=U1",
            "{\"accountId\":\"U1\",\"accountType\":\"atlassian\",\"active\":true,\"emailAddress\":\" \"}"),
        Arguments.of("/project/P1/permissionscheme", "{invalid json"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "{\"total\":1}",
        "{\"startAt\":0,\"total\":1,\"maxResults\":50,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":50,\"isLast\":false,\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":0,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":-1,\"total\":0,\"maxResults\":50,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":0,\"total\":-1,\"maxResults\":50,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":0,\"total\":2147483648,\"maxResults\":50,\"isLast\":false,\"values\":[]}",
        "{\"startAt\":0,\"total\":0.0,\"maxResults\":50,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":50,\"isLast\":\"true\",\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":50,\"isLast\":true}",
        "{\"startAt\":1,\"total\":1,\"maxResults\":50,\"isLast\":true,\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":50,\"isLast\":true,\"nextPage\":\"https://example.atlassian.net/rest/api/3/group/member?startAt=0\",\"values\":[]}",
        "{\"startAt\":0,\"total\":0,\"maxResults\":50,\"isLast\":true,\"values\":[{}]}"
      })
  @DisplayName("그룹 페이지의 필수 메타데이터 오류를 거부한다")
  void invalidGroupPageIsFailed(String body) {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(body, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.observations().groups()).isEmpty();
  }

  @Test
  @DisplayName("그룹 offset 반복은 실패다")
  void groupPaginationDoesNotAdvanceIsFailed() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 2, jira.userJson("U2")),
        JiraAccessScenario.page(0, 2, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @Test
  @DisplayName("그룹 마지막 신호와 total 상충은 실패다")
  void groupTerminalMetadataConflictIsFailed() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 2, jira.userJson("U2")).replace("false", "true"), "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @Test
  @DisplayName("그룹 전체 인원수가 페이지 사이에 바뀌면 실패다")
  void changedGroupTotalIsFailed() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 2, jira.userJson("U2")),
        JiraAccessScenario.page(1, 3, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @Test
  @DisplayName("그룹 멤버 중복은 잘린 전체 집합으로 실패시킨다")
  void duplicateGroupMemberIsFailed() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 2, jira.userJson("U2")),
        JiraAccessScenario.page(1, 2, jira.userJson("U2")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"id\":100}",
        "{\"id\":101,\"actors\":[]}",
        "{\"id\":100,\"actors\":[{}]}",
        "{\"id\":100,\"actors\":[{\"type\":\"futureActor\"}]}"
      })
  @DisplayName("역할 응답 누락과 상충은 실패다")
  void malformedRoleFailsProject(String body) {
    // given
    jira.browse("P1", "{\"type\":\"projectRole\",\"parameter\":\"100\"}");
    jira.answer("/project/P1/role/100", body);
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"accountId\":\"~reserved\",\"accountType\":\"atlassian\",\"active\":true}",
        "{\"accountId\":\"\\ud800\",\"accountType\":\"atlassian\",\"active\":true}"
      })
  @DisplayName("잘못된 원천 사용자 식별자는 관측 실패로 분류한다")
  void invalidAccountIdFailsClosed(String body) {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 1, body), "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @Test
  @DisplayName("예약된 합성 그룹 이름을 원천 그룹으로 받아들이지 않는다")
  void reservedGroupIdFailsClosed() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"~fake\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.atlassian.net/page",
        "https://example.atlassian.net/page?startAt=0",
        "https://example.atlassian.net/page?startAt=2",
        "%%%",
        "https://example.atlassian.net/page?startAt=1&startAt=2"
      })
  @DisplayName("다음 페이지 URL의 offset 상충도 거부한다")
  void conflictingNextPageIsFailed(String next) {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    String first = JiraAccessScenario.page(0, 2, jira.userJson("U2"));
    jira.groupPages(
        first.substring(0, first.length() - 1) + ",\"nextPage\":\"" + next + "\"}",
        JiraAccessScenario.page(1, 2, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
  }
}
