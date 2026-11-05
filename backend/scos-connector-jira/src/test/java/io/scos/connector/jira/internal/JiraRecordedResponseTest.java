package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.source.SourceIdentityRecord;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 어댑터는 생성 응답과 실측 모양 표본을 같은 계약으로 읽는다")
class JiraRecordedResponseTest {
  private final JiraAccessScenario jira = new JiraAccessScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("기본 사용자는 이메일 없이 accountId 신원으로 보존한다")
  void defaultUserHasNoEmail(boolean recorded) {
    jira.responseShape(recorded);
    // when·then
    assertThat(jira.readProject("P1").observations().identities())
        .containsExactly(
            new SourceIdentityRecord(
                "U1", Optional.empty(), false, false, false, JiraAccessScenario.NOW));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("이메일 숨김·security:null인 이슈는 확인한 프로젝트 권한으로 관측한다")
  void issueWithExplicitNullSecurityIsObserved(boolean recorded) {
    jira.issueResponseShape(recorded);
    jira.collect("issue:10001");
    // then
    assertThat(jira.acl("issue:10001").status()).isEqualTo(Status.OK);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("접근 수준 없는 team-managed 프로젝트는 정책으로 차단한다")
  void teamManagedHasNoPublicGrant(boolean recorded) {
    jira.teamManagedResponseShape(recorded);
    // when·then
    assertThat(jira.readProject("P1").decision()).isEqualTo(Decision.POLICY_BLOCKED);
  }
}
