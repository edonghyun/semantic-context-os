package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailureException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 문서 ACL은 실제 CM grant와 명시 보안 수준을 따른다 (T-b17)")
class JiraAccessCollectionTest {
  private final JiraAccessScenario s = new JiraAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("보안 없는 이슈는 프로젝트와 Browse에 명시된 보고자만 허용한다")
  void issueWithoutSecurityUsesProjectAndGrantedReporter() {
    // given
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}", "{\"type\":\"reporter\"}");
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001"))
        .isEqualTo(
            Set.of(
                "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6",
                "user:jira-a:U1"));
  }

  @Test
  @DisplayName("담당자 holder가 없으면 담당자 직접 키를 추가하지 않는다")
  void assigneeWithoutBrowseGrantGetsNoKey() {
    // given
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001"))
        .containsExactly(
            "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("보안 수준 이슈는 교집합 level hash 키만 갖는다")
  void issueWithSecurityUsesOnlyLevelKey() {
    // given
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}", "{\"type\":\"reporter\"}");
    s.security("P1");
    s.issue("10001", "P1", ",\"security\":{\"id\":\"L1\"}");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001"))
        .containsExactly(
            "group:jira-a:~level%3AL1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @ParameterizedTest
  @ValueSource(strings = {"project:P1", "component:10", "version:20"})
  @DisplayName("프로젝트·컴포넌트·버전은 확인한 소속 프로젝트 hash 키를 갖는다")
  void projectArtifactUsesProjectKey(String id) {
    // given
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    s.artifact("component", "10", "P1");
    s.artifact("version", "20", "P1");
    // when
    s.collect(id);
    // then
    assertThat(s.keys(id))
        .containsExactly(
            "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("security 누락은 명시 null과 달리 FAILED·미완료다")
  void missingSecurityFieldIsNotNoSecurity() {
    // given
    s.issue("10001", "P1", "");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }
}
