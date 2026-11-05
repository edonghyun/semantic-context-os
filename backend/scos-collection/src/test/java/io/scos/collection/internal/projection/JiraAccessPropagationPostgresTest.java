package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.projection.JiraAccessScenario.BodyState;
import io.scos.collection.internal.projection.JiraAccessScenario.GrantState;
import io.scos.spi.access.DocumentAcl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("Jira 권한 관측은 실제 수집과 투영을 거쳐 제한 문서 읽기에 전달된다")
class JiraAccessPropagationPostgresTest {
  private JiraAccessScenario scenario;

  @BeforeEach
  void setUp() throws Exception {
    scenario = new JiraAccessScenario();
  }

  @AfterEach
  void close() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("전체 본문 재수집의 비관측 페이지는 기존 관측 ACL의 열람을 보존한다")
  void fullBodyRecrawlDoesNotOverwriteObservedAcl() throws Exception {
    // given
    scenario.givenVisibleIssue();
    // when
    try (var paused = scenario.pauseAfterBody()) {
      scenario.project();
      // then
      assertThat(scenario.acl("10001").status()).isEqualTo(DocumentAcl.Status.OK);
      assertThat(scenario.readCount("U1", "10001")).isEqualTo(1);
      assertThat(paused.isCollecting()).isTrue();
    }
  }

  @Test
  @DisplayName("보안 수준에 없는 Browse 사용자는 문서를 읽을 수 없다")
  void securityLevelExcludesBrowseOnlyUser() {
    // given
    scenario.security("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("Browse와 보안 수준을 모두 가진 사용자는 문서를 읽을 수 있다")
  void securityLevelKeepsLevelMember() {
    // given
    scenario.security("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.readCount("U2", "10001")).isEqualTo(1);
  }

  @Test
  @DisplayName("이메일이 숨겨진 관측 신원도 기존 관리자 연결로 열람할 수 있다")
  void hiddenEmailAdminLinkGetsObservedKeys() {
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.hiddenIdentityObserved("U2")).isTrue();
    assertThat(scenario.readCount("U2", "10001")).isEqualTo(1);
  }

  @Test
  @DisplayName("역할 actor가 제거되면 이슈를 편집하지 않아도 열람이 회수된다")
  void removedRoleActorLosesAccessWithoutIssueEdit() {
    // given
    scenario.givenVisibleIssue();
    long changes = scenario.bodyChanges();
    scenario.roleUsers("100", "U2");
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(scenario.bodyState(run)).isEqualTo(new BodyState(0, 0, changes));
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("역할에 연결한 그룹에서 탈퇴하면 이슈 변경 없이 열람이 회수된다")
  void removedGroupMemberLosesAccessWithoutIssueEdit() {
    // given
    scenario.givenGroupSharedIssue();
    long changes = scenario.bodyChanges();
    scenario.groupMembers("U1");
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(scenario.bodyState(run)).isEqualTo(new BodyState(0, 0, changes));
    assertThat(scenario.readCount("U2", "10001")).isZero();
  }

  @Test
  @DisplayName("역할 관측이 거부되면 기존 문서는 FAILED로 닫힌다")
  void deniedPolicyReadRevokesPreviouslyGrantedDocument() {
    // given
    scenario.givenVisibleIssue();
    scenario.denyProject("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.acl("10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("미완료 run도 명시적으로 빈 기존 원천 그룹을 축소한다")
  void incompleteRunClearsKnownDependentGroup() {
    // given
    scenario.givenGroupSharedIssue();
    scenario.groupMembers();
    scenario.denyProject("200");
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.members("G1")).isEmpty();
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("일부 프로젝트 관측이 실패한 run에서는 새 구성원을 발급하지 않는다")
  void newMemberIsNotGrantedByIncompleteDirectory() {
    // given
    scenario.givenVisibleIssue();
    scenario.roleUsers("100", "U1", "U2", "U3");
    scenario.denyProject("200");
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.groupRows(scenario.currentGroup("10001"))).isZero();
    assertThat(scenario.readCount("U3", "10001")).isZero();
  }

  @Test
  @DisplayName("기존 CM 프로젝트 유형이 상충하면 과거 열람을 회수한다")
  void typeChangeToUnknownRevokesOldGroup() {
    // given
    scenario.givenVisibleIssue();
    scenario.conflictingProject("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.acl("10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("보안 수준만 좁히는 관측은 본문을 다시 임베딩하지 않는다")
  void aclOnlyRestrictionDoesNotEmbedBody() {
    // given
    scenario.givenVisibleIssue();
    scenario.security("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.readCount("U1", "10001")).isZero();
    assertThat(scenario.embeddingCalls()).isZero();
  }

  @Test
  @DisplayName("CM 하나의 관측 거부는 독립 프로젝트의 새 발급도 막는다")
  void oneDeniedProjectBlocksOtherProjectGrants() {
    // given
    scenario.denyProject("100");
    scenario.roleUsers("200", "U3");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.grantState("20001", "U3"))
        .isEqualTo(new GrantState(AccessResult.INCOMPLETE, 0, 0));
  }

  @Test
  @DisplayName("다른 CM 거부가 지속되면 집합 축소 뒤 새 hash도 재발급되지 않는다")
  void changedSetCannotRegrantWhileAnotherProjectDenied() {
    // given
    scenario.givenVisibleIssue();
    scenario.requireVisible("U2", "20001");
    scenario.denyProject("100");
    scenario.roleUsers("200", "U2");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.grantState("20001", "U2"))
        .isEqualTo(new GrantState(AccessResult.INCOMPLETE, 0, 0));
  }

  @Test
  @DisplayName("미검증 TM 이슈는 정상 HTTP 본문이어도 FAILED로 문서 읽기를 막는다")
  void unverifiedTeamManagedIssueRemainsBlocked() {
    // given
    scenario.teamProject("100");
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.acl("10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(scenario.readCount("U1", "10001")).isZero();
  }

  @Test
  @DisplayName("TM과 A9 정책 차단은 완전히 관측한 CM의 발급을 막지 않는다")
  void mixedSiteCompanyGrantIsIssuedWhileTeamManagedBlocked() {
    // given
    scenario.teamProject("100");
    scenario.policyBlockedArtifact();
    // when
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
    assertThat(scenario.readCount("U2", "20001")).isEqualTo(1);
    assertThat(scenario.readCount("U2", "10001")).isZero();
  }
}
