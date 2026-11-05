package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Jira 매 run 재평가는 저장 carrier와 같은 개인 인증을 사용한다 (T-b17)")
class JiraAccessRefreshTest {
  private final JiraAccessScenario s = new JiraAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("검색에서 사라져도 저장 이슈를 직접 재평가한다")
  void invisibleProjectStillReevaluatesStoredCarriers() {
    // given
    s.storedIssue("10001", "P1", true);
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.OK);
    assertThat(s.requestsTo("/project/search")).isZero();
  }

  @Test
  @DisplayName("첫 프로젝트 실패 뒤에도 다음 프로젝트의 회수를 관측한다")
  void laterProjectIsCheckedAfterEarlierProjectFails() {
    // given
    s.response("/project/P1", 403, "{}");
    s.companyProject("P2");
    s.browse("P2");
    // when
    var failure = catchThrowable(() -> s.collect("project:P1", "project:P2"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.keys("project:P2"))
        .containsExactly(
            "group:jira-a:~project%3AP2%3A4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945");
  }

  @Test
  @DisplayName("혼합 유형은 CM만 후보로 내고 UNKNOWN 때문에 미완료다")
  void mixedStylesUseSeparatePolicies() {
    // given
    s.teamProject("P1");
    s.companyProject("P2");
    s.browse("P2", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    s.unknownProject("P3");
    // when
    var failure = catchThrowable(() -> s.collect("project:P1", "project:P2", "project:P3"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.keys("project:P1")).isEmpty();
    assertThat(s.acl("project:P2").status()).isEqualTo(DocumentAcl.Status.OK);
  }

  @Test
  @DisplayName("본문과 접근은 같은 credential ref를 해소한다")
  void samePersonalCredentialIsUsedForAccess() {
    // when
    s.collectBodyAndAccess();
    // then
    assertThat(s.credentialRefs()).containsExactly("env:TEST_ONLY", "env:TEST_ONLY");
  }

  @Test
  @DisplayName("본문 수집은 접근 수집이 있어도 명시 notObserved를 낸다")
  void bodyCollectionKeepsNotObserved() {
    // when
    s.collectBodyAndAccess();
    // then
    assertThat(s.bodyAcls()).containsExactly(RawAcl.notObserved());
  }

  @Test
  @DisplayName("새 CM ACL은 같은 최종 집합 snapshot의 hash 그룹만 참조한다")
  void newJiraAclNeverUsesUnboundGrantGroup() {
    // given
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    // when
    s.collect("project:P1");
    // then
    assertThat(s.keys("project:P1"))
        .isEqualTo(
            Set.of(
                "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6"));
    assertThat(s.grantGroupIds())
        .containsExactly(
            "~project:P1:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("같은 어댑터도 다음 run에는 프로젝트 권한을 다시 읽는다")
  void nextRunDoesNotReuseDirectory() {
    // given
    s.collect("project:P1");
    s.browse("P1", "{\"type\":\"user\",\"parameter\":\"U2\"}");
    s.advanceObservation();
    // when
    s.collect("project:P1");
    // then
    assertThat(s.keys("project:P1"))
        .containsExactly(
            "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
    assertThat(s.requestsTo("/project/P1")).isEqualTo(2);
  }

  @Test
  @DisplayName("저장 JSON 파싱 실패도 대상을 닫고 후속 대상을 계속한다")
  void malformedStoredContentStillChecksLaterTarget() {
    // given
    s.storedContent("issue:10001", "{");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001", "project:P1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(s.acl("project:P1").status()).isEqualTo(DocumentAcl.Status.OK);
  }
}
