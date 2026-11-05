package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.source.SourceIdentityRecord;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 접근 디렉터리는 CM grant 전체 집합을 신원과 함께 계산한다 (T-b16)")
class JiraAccessDirectoryTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @Test
  @DisplayName("직접 사용자·역할·그룹을 Browse 사용자로 펼친다")
  void roleActorsExpandToBrowseUsers() {
    // given
    jira.browse(
        "P1",
        "{\"type\":\"user\",\"parameter\":\"U1\"}",
        "{\"type\":\"projectRole\",\"parameter\":\"100\"}");
    jira.role(
        "P1",
        "{\"type\":\"atlassian-user-role-actor\",\"actorUser\":{\"accountId\":\"U2\"}}",
        "{\"type\":\"atlassian-group-role-actor\",\"actorGroup\":{\"groupId\":\"G1\"}}");
    jira.groupPages(JiraAccessScenario.page(0, 1, jira.userJson("U3")), "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactlyInAnyOrder("U1", "U2", "U3");
  }

  @Test
  @DisplayName("보안 수준 사용자는 Browse와 교집합한다")
  void levelIntersectsBrowseUsers() {
    // given
    jira.browse(
        "P1",
        "{\"type\":\"user\",\"parameter\":\"U1\"}",
        "{\"type\":\"user\",\"parameter\":\"U2\"}",
        "{\"type\":\"user\",\"parameter\":\"U3\"}");
    jira.security("P1");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels().get("L1").snapshot().members()).containsExactly("U2");
  }

  @Test
  @DisplayName("숨긴 이메일도 accountId 신원을 보존한다")
  void hiddenEmailStillProducesIdentity() {
    // given
    jira.userResponse("U1", "{\"accountId\":\"U1\",\"accountType\":\"atlassian\",\"active\":true}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.observations().identities())
        .containsExactly(
            new SourceIdentityRecord(
                "U1", Optional.empty(), false, false, false, JiraAccessScenario.NOW));
  }

  @Test
  @DisplayName("고객 계정은 guest 신원으로 저장한다")
  void customerIsGuest() {
    // given
    jira.userResponse("U1", jira.customerUser("U1"));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.observations().identities())
        .extracting(SourceIdentityRecord::guest)
        .containsExactly(true);
  }

  @Test
  @DisplayName("공유 보안 수준은 모든 적용 프로젝트 Browse 밖을 허용하지 않는다")
  void crossProjectLevelCannotBroadenBrowse() {
    // given
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.security("P1", "P2");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels().get("L1").snapshot().members()).isEmpty();
  }

  @Test
  @DisplayName("공유 보안 수준의 hash는 최종 교집합에 바인딩한다")
  void sharedLevelDigestUsesFinalIntersection() {
    // given
    jira.companyProject("P2");
    jira.browse(
        "P1",
        "{\"type\":\"user\",\"parameter\":\"U1\"}",
        "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.browse("P2", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.security("P1", "P2");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels().get("L1").key().toString())
        .isEqualTo(
            "group:jira-a:~level%3AL1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("그룹 두 페이지는 완전한 snapshot 하나가 된다")
  void groupPagesProduceOneCompleteSnapshot() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(
        JiraAccessScenario.page(0, 2, jira.userJson("U2")),
        JiraAccessScenario.page(1, 2, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.observations().groups())
        .filteredOn(g -> g.groupId().equals("G1"))
        .extracting(g -> g.members())
        .containsExactly(Set.of("U2", "U3"));
  }

  @Test
  @DisplayName("프로젝트 리드는 명시 grant가 있을 때만 포함한다")
  void projectLeadGrantResolvesIdentity() {
    // given
    jira.browse("P1", "{\"type\":\"projectLead\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactly("U1");
  }

  @Test
  @DisplayName("동적 grant는 이슈별 조건으로 보존한다")
  void dynamicGrantsRemainIssueConditions() {
    // given
    jira.browse(
        "P1",
        "{\"type\":\"reporter\"}",
        "{\"type\":\"assignee\"}",
        "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10001\"}",
        "{\"type\":\"groupCustomField\",\"parameter\":\"customfield_10002\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.issueGrants())
        .containsExactlyInAnyOrder(
            new JiraAccessDirectory.IssueGrant("reporter", ""),
            new JiraAccessDirectory.IssueGrant("assignee", ""),
            new JiraAccessDirectory.IssueGrant("userCustomField", "customfield_10001"),
            new JiraAccessDirectory.IssueGrant("groupCustomField", "customfield_10002"));
  }

  @Test
  @DisplayName("이슈별 grant는 프로젝트 사용자에 합치지 않는다")
  void dynamicGrantsDoNotBroadenBrowseUsers() {
    // given
    jira.browse(
        "P1",
        "{\"type\":\"reporter\"}",
        "{\"type\":\"assignee\"}",
        "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10001\"}",
        "{\"type\":\"groupCustomField\",\"parameter\":\"customfield_10002\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"applicationRole", "anyone", "sd.customer.portal.only", "futureHolder"})
  @DisplayName("비지원 holder는 아무도 허용하지 않는다")
  void unknownHolderGrantsNobody(String holder) {
    // given
    jira.browse("P1", "{\"type\":\"" + holder + "\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).isEmpty();
  }

  @Test
  @DisplayName("보안 수준의 동적 holder는 정적 사용자 집합을 넓히지 않는다")
  void dynamicSecurityMembersAreOmitted() {
    // given
    jira.security("P1");
    jira.levelMembers("{\"type\":\"reporter\"}", "{\"type\":\"assignee\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels().get("L1").snapshot().members()).isEmpty();
  }

  @Test
  @DisplayName("프로젝트 관측을 같은 run에서 재사용한다")
  void projectIsReadOnceWithinRun() {
    // given
    jira.readProject("P1");
    // when
    jira.readProject("P1");
    // then
    assertThat(jira.requestsTo("/project/P1")).isEqualTo(1);
  }

  @Test
  @DisplayName("그룹 이름 holder도 전체 사용자를 펼친다")
  void legacyGroupNameUsesGroupnameQuery() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"parameter\":\"Readers\"}");
    jira.answer(
        "/group/member?groupname=Readers&includeInactiveUsers=true&startAt=0&maxResults=50",
        JiraAccessScenario.page(0, 1, jira.userJson("U2")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactly("U2");
  }

  @Test
  @DisplayName("앱 사용자는 grant를 받지 않는다")
  void appAccountDoesNotGrantUser() {
    // given
    jira.userResponse("U1", jira.appUser("U1"));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).isEmpty();
  }

  @Test
  @DisplayName("비활성 사용자는 grant를 받지 않는다")
  void inactiveAccountDoesNotGrantUser() {
    // given
    jira.userResponse("U1", jira.inactiveUser("U1"));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).isEmpty();
  }

  @Test
  @DisplayName("다른 그룹의 이름과 id가 같아도 캐시를 섞지 않는다")
  void groupNameDoesNotReuseDifferentGroupId() {
    // given
    jira.browse(
        "P1", "{\"type\":\"group\",\"value\":\"G1\"}", "{\"type\":\"group\",\"parameter\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 1, jira.userJson("U2")), "{}");
    jira.answer(
        "/group/member?groupname=G1&includeInactiveUsers=true&startAt=0&maxResults=50",
        JiraAccessScenario.page(0, 1, jira.userJson("U3")));
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactlyInAnyOrder("U2", "U3");
  }

  @Test
  @DisplayName("같은 신원을 늦게 관측해도 상충으로 보지 않는다")
  void sameAccountAtLaterTimeIsNotConflict() {
    // given
    jira.readProject("P1");
    jira.advanceObservation();
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 1, jira.userJson("U1")), "{}");
    // when
    var project = jira.readProject("P2");
    // then
    assertThat(project.browseUsers()).containsExactly("U1");
  }

  @Test
  @DisplayName("grant snapshot을 sink에 전달한다")
  void completeGroupObservationIsEmitted() {
    // when
    var groups = jira.observedGroups("P1");
    // then
    assertThat(groups)
        .containsExactly(
            "~project:P1:0e4c04c23102f939fe8f36ba87cca7a2e93869b57e0a1e1c36675e794fb2b416");
  }
}
