package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.connector.jira.internal.JiraAccessDirectory.ProjectStyle;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 필수 관측 실패는 grant를 비우고 소스 전체성 실패로 남긴다 (T-b16, 설계 §6.1)")
class JiraAccessFailureTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 429, 500})
  @DisplayName("개인 계정의 필수 API 실패는 관측 실패로 반환한다")
  void deniedDependencyFailsProject(int status) {
    // given
    jira.response("/project/P1/permissionscheme", status, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.projectGrant()).isEmpty();
    assertThat(project.failure()).isPresent();
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404})
  @DisplayName("역할 actor 거부를 빈 역할로 간주하지 않는다")
  void deniedRoleActorsAreNotEmptyRole(int status) {
    // given
    jira.browse("P1", "{\"type\":\"projectRole\",\"parameter\":\"100\"}");
    jira.response("/project/P1/role/100", status, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.browseUsers()).isEmpty();
  }

  @Test
  @DisplayName("그룹 두 번째 페이지 거부는 의존 프로젝트를 실패시킨다")
  void groupSecondPageDeniedFailsDependentProject() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 2, jira.userJson("U2")), "{}");
    jira.response(
        "/group/member?groupId=G1&includeInactiveUsers=true&startAt=1&maxResults=50", 403, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.observations().groups()).isEmpty();
  }

  @Test
  @DisplayName("그룹 전체성 실패는 소스 완료를 허용하지 않는다")
  void incompleteGroupDoesNotCompleteDirectory() {
    // given
    jira.browse("P1", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 2, jira.userJson("U2")), "{}");
    jira.readProject("P1");
    // when
    var failure = catchThrowable(jira::requireComplete);
    // then
    assertThat(failure)
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("Jira access");
  }

  @Test
  @DisplayName("정책 차단만 있으면 소스 완료 후보를 유지한다")
  void policyBlockedProjectsDoNotPreventCompletion() {
    // given
    jira.answer(
        "/project/P1",
        "{\"id\":\"P1\",\"style\":\"next-gen\",\"simplified\":true,\"projectTypeKey\":\"software\"}");
    jira.readProject("P1");
    // when
    var failure = catchThrowable(jira::requireComplete);
    // then
    assertThat(failure).isNull();
  }

  @Test
  @DisplayName("유형 UNKNOWN은 소스 완료를 막는다")
  void unknownProjectStylePreventsCompletion() {
    // given
    jira.answer("/project/P1", "{\"id\":\"P1\",\"projectTypeKey\":\"software\"}");
    jira.readProject("P1");
    // when
    var failure = catchThrowable(jira::requireComplete);
    // then
    assertThat(failure)
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("project style");
  }

  @Test
  @DisplayName("실패 뒤 독립 CM 프로젝트를 계속 관측한다")
  void failedProjectDoesNotStopIndependentProject() {
    // given
    jira.response("/project/P1", 403, "{}");
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.readProject("P1");
    // when
    var project = jira.readProject("P2");
    // then
    assertThat(project.decision()).isEqualTo(Decision.SUPPORTED);
    assertThat(project.browseUsers()).containsExactly("U2");
  }

  @Test
  @DisplayName("혼합 사이트 TM 차단은 독립 CM grant 발급을 막지 않는다")
  void teamManagedBlockDoesNotPreventCompanyGrant() {
    // given
    jira.answer(
        "/project/P1",
        "{\"id\":\"P1\",\"style\":\"next-gen\",\"simplified\":true,\"projectTypeKey\":\"software\"}");
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    jira.readProject("P1");
    // when
    var project = jira.readProject("P2");
    // then
    assertThat(project.browseUsers()).containsExactly("U2");
    assertThat(project.projectGrant()).isPresent();
  }

  @Test
  @DisplayName("신원 유형 누락은 사용자 집합을 실패시킨다")
  void missingAccountTypeDoesNotGrantUser() {
    // given
    jira.userResponse("U1", "{\"accountId\":\"U1\",\"active\":true}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.observations().identities()).isEmpty();
  }

  @Test
  @DisplayName("공유 수준의 적용 프로젝트 유형이 불명이면 수준을 확정하지 않는다")
  void unknownAppliedProjectCannotConfirmSharedLevel() {
    // given
    jira.companyProject("P2");
    jira.answer("/project/P2", "{\"id\":\"P2\",\"projectTypeKey\":\"software\"}");
    jira.security("P1", "P2");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels()).isEmpty();
    assertThat(project.levelFailure()).isPresent();
  }

  @Test
  @DisplayName("미지원 제품과 공유한 수준도 프로젝트 Browse를 무효화하지 않는다")
  void unsupportedAppliedProjectKeepsBrowseGrant() {
    // given
    jira.companyProject("P2");
    jira.answer(
        "/project/P2",
        "{\"id\":\"P2\",\"style\":\"classic\",\"simplified\":false,\"projectTypeKey\":\"service_desk\"}");
    jira.security("P1", "P2");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.browseUsers()).containsExactly("U1");
    assertThat(project.levelFailure().orElseThrow().message())
        .isEqualTo("Unobservable Jira shared level project");
  }

  @Test
  @DisplayName("보안 수준 실패 원인을 Browse 실패와 구분해서 보존한다")
  void levelFailureIsSeparateFromBrowseFailure() {
    // given
    jira.answer("/issuesecurityschemes/project?projectId=P1&startAt=0&maxResults=50", "[]");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.failure()).isEmpty();
    assertThat(project.levels()).isEmpty();
    assertThat(project.levelFailure().orElseThrow().kind())
        .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE);
  }

  @Test
  @DisplayName("보안 수준 실패에도 프로젝트 snapshot을 내보낸다")
  void levelFailureEmitsProjectGroup() {
    // given
    jira.response("/issuesecurityschemes/project?projectId=P1&startAt=0&maxResults=50", 403, "{}");
    // when
    var groups = jira.observedGroups("P1");
    // then
    assertThat(groups)
        .containsExactly(
            "~project:P1:0e4c04c23102f939fe8f36ba87cca7a2e93869b57e0a1e1c36675e794fb2b416");
  }

  @Test
  @DisplayName("보안 수준 API가 거부돼도 증명한 프로젝트 grant는 유지한다")
  void levelObservationFailureKeepsProjectGrant() {
    // given
    jira.response("/issuesecurityschemes/project?projectId=P1&startAt=0&maxResults=50", 403, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.SUPPORTED);
    assertThat(project.projectGrant().orElseThrow().snapshot().members()).containsExactly("U1");
  }

  @Test
  @DisplayName("보안 수준 관측 실패는 소스 완료를 막는다")
  void levelObservationFailurePreventsCompletion() {
    // given
    jira.response("/issuesecurityschemes/project?projectId=P1&startAt=0&maxResults=50", 403, "{}");
    jira.readProject("P1");
    // when
    var failure = catchThrowable(jira::requireComplete);
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class).hasMessageContaining("403");
  }

  @Test
  @DisplayName("보안 수준 관측 중 취소도 부분 성공 결과로 흡수하지 않는다")
  void cancellationDuringLevelObservationIsPropagated() {
    // given
    jira.cancelDuringLevelObservation();
    // when
    var failure = catchThrowable(() -> jira.readProject("P1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(((CallFailureException) failure).failure().kind())
        .isEqualTo(CallFailure.Kind.CANCELLED);
  }

  @Test
  @DisplayName("보안 스킴 없음의 완전 관측은 미확정 수준과 구분한다")
  void noSecuritySchemeIsCompleteLevelObservation() {
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.levels()).isEmpty();
    assertThat(project.levelFailure()).isEmpty();
  }

  @Test
  @DisplayName("취소는 프로젝트 실패 결과로 흡수하지 않는다")
  void cancellationIsPropagated() {
    // given
    jira.cancel();
    // when
    var failure = catchThrowable(() -> jira.readProject("P1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(((CallFailureException) failure).failure().kind())
        .isEqualTo(CallFailure.Kind.CANCELLED);
    assertThat(jira.requestsTo("/project/P1")).isZero();
  }

  @Test
  @DisplayName("비지원 holder만 있는 스킴은 명시적 정책 차단이다")
  void unsupportedOnlySchemeIsPolicyBlocked() {
    // given
    jira.browse("P1", "{\"type\":\"applicationRole\"}", "{\"type\":\"anyone\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.POLICY_BLOCKED);
    assertThat(project.projectGrant()).isEmpty();
  }

  @Test
  @DisplayName("지원 grant와 비지원 holder가 섞이면 지원 부분만 사용한다")
  void mixedHolderKeepsSupportedGrant() {
    // given
    jira.browse("P1", "{\"type\":\"applicationRole\"}", "{\"type\":\"user\",\"parameter\":\"U1\"}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.decision()).isEqualTo(Decision.SUPPORTED);
    assertThat(project.browseUsers()).containsExactly("U1");
  }

  @Test
  @DisplayName("읽은 프로젝트도 취소 뒤에는 결과를 다시 반환하지 않는다")
  void cancellationPrecedesCachedProject() {
    // given
    jira.readProject("P1");
    jira.cancel();
    // when
    var failure = catchThrowable(() -> jira.readProject("P1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(((CallFailureException) failure).failure().kind())
        .isEqualTo(CallFailure.Kind.CANCELLED);
  }

  @Test
  @DisplayName("상충 신원도 병합 단계에서 회수하도록 두 관측을 보존한다")
  void conflictingIdentityPreservesRestrictionObservations() {
    // given
    jira.readProject("P1");
    jira.advanceObservation();
    jira.companyProject("P2");
    jira.browse("P2", "{\"type\":\"group\",\"value\":\"G1\"}");
    jira.groupPages(JiraAccessScenario.page(0, 1, jira.inactiveUser("U1")), "{}");
    // when
    var project = jira.readProject("P2");
    // then
    assertThat(project.decision()).isEqualTo(Decision.OBSERVATION_FAILED);
    assertThat(project.observations().identities())
        .filteredOn(identity -> identity.sourceUserId().equals("U1"))
        .extracting(identity -> identity.deleted())
        .containsExactlyInAnyOrder(false, true);
  }

  @Test
  @DisplayName("스킴 거부도 이미 확인한 프로젝트 유형은 보존한다")
  void deniedSchemePreservesKnownProjectStyle() {
    // given
    jira.response("/project/P1/permissionscheme", 403, "{}");
    // when
    var project = jira.readProject("P1");
    // then
    assertThat(project.style()).isEqualTo(ProjectStyle.COMPANY_MANAGED);
  }
}
