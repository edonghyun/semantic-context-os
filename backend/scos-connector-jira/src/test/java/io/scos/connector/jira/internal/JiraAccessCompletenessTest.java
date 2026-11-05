package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 정책 차단은 완료 후보·관측 실패는 미완료다 (설계 §6.1)")
class JiraAccessCompletenessTest {
  private final JiraAccessScenario s = new JiraAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("미개방 TM의 security null도 이슈 조회 없이 차단한다")
  void teamManagedSecurityNullIsNotUnrestricted() {
    // given
    s.teamProject("P1");
    s.storedIssue("10001", "P1", true);
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(s.keys("issue:10001")).isEmpty();
    assertThat(s.requestsTo("/issue/10001")).isZero();
  }

  @Test
  @DisplayName("미개방 TM의 security 누락도 이슈 조회 없이 차단한다")
  void missingTeamManagedSecurityFieldIsFailed() {
    // given
    s.teamProject("P1");
    s.storedIssue("10001", "P1", false);
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(s.requestsTo("/issue/10001")).isZero();
  }

  @Test
  @DisplayName("A9와 미개방 TM 정책 차단만 있으면 정상 반환한다")
  void policyBlockedCarriersDoNotPreventCompletion() {
    // given
    s.teamProject("P1");
    s.storedIssue("10001", "P1", true);
    // when
    var failure =
        catchThrowable(
            () -> s.collect("issue:10001", "filter:1", "dashboard:2", "board:3", "sprint:4"));
    // then
    assertThat(failure).isNull();
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"filter:1", "dashboard:2", "board:3", "sprint:4"})
  @DisplayName("공유 artifact는 각각 빈 FAILED로 차단한다")
  void sharedArtifactIsFailed(String id) {
    // when
    s.collect(id);
    // then
    assertThat(s.acl(id).status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(s.keys(id)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404})
  @DisplayName("CM 프로젝트 필수 읽기 실패를 전달하고 미완료로 끝낸다")
  void projectObservationFailureMakesAccessIncomplete(int status) {
    // given
    s.response("/project/P1", status, "{}");
    // when
    var failure = catchThrowable(() -> s.collect("project:P1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("project:P1").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404})
  @DisplayName("보이지 않는 CM 이슈는 FAILED를 전달하고 미완료로 끝낸다")
  void invisibleIssueMakesAccessIncomplete(int status) {
    // given
    s.response(
        "/issue/10001?fields=project,security,reporter,assignee&expand=schema", status, "{}");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"project:P1", "component:10", "version:20"})
  @DisplayName("미개방 TM의 프로젝트 artifact도 각각 차단한다")
  void blockedProjectArtifactIsFailed(String id) {
    // given
    s.teamProject("P1");
    s.artifact("component", "10", "P1");
    s.artifact("version", "20", "P1");
    // when
    s.collect(id);
    // then
    assertThat(s.acl(id).status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(s.keys(id)).isEmpty();
  }
}
