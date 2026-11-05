package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Jira 동적 Browse는 일치하는 필드 schema·신원을 요구한다 (T-b17)")
class JiraAccessCustomFieldTest {
  private final JiraAccessScenario s = new JiraAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("userCustomField는 일치하는 schema의 사용자만 직접 허용한다")
  void userCustomFieldRequiresMatchingSchema() {
    // given
    s.browse("P1", "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000", "{\"type\":\"user\",\"customId\":10000}", "{\"accountId\":\"U3\"}");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).contains("user:jira-a:U3");
  }

  @Test
  @DisplayName("groupCustomField는 확인한 그룹 사용자를 직접 허용한다")
  void groupCustomFieldExpandsUsers() {
    // given
    s.browse("P1", "{\"type\":\"groupCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000",
        "{\"type\":\"array\",\"items\":\"group\",\"customId\":10000}",
        "[{\"groupId\":\"G1\",\"name\":\"Viewers\"}]");
    s.groupPages(
        JiraAccessScenario.page(0, 2, s.userJson("U2")),
        JiraAccessScenario.page(1, 2, s.userJson("U3")));
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).contains("user:jira-a:U2", "user:jira-a:U3");
  }

  @Test
  @DisplayName("customId가 다른 schema는 권한을 만들지 않고 미완료다")
  void mismatchedCustomFieldSchemaIsFailed() {
    // given
    s.browse("P1", "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000", "{\"type\":\"user\",\"customId\":10001}", "{\"accountId\":\"U3\"}");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @Test
  @DisplayName("직접 Browse라도 비활성 사용자는 직접 키를 얻지 않는다")
  void inactiveReporterHasNoDirectKey() {
    // given
    s.browse("P1", "{\"type\":\"reporter\"}");
    s.userResponse("U1", s.inactiveUser("U1"));
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).doesNotContain("user:jira-a:U1");
  }

  @Test
  @DisplayName("보고자와 custom field가 섞여도 명시 필드만 요청한다")
  void customFieldAndReporterUseExactFieldSelection() {
    // given
    s.browse(
        "P1",
        "{\"type\":\"reporter\"}",
        "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000", "{\"type\":\"user\",\"customId\":10000}", "{\"accountId\":\"U3\"}");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).contains("user:jira-a:U1", "user:jira-a:U3");
  }

  @Test
  @DisplayName("이슈 custom field에서 관측한 원천 그룹도 페이지에 보낸다")
  void dynamicGroupSnapshotIsEmitted() {
    // given
    s.browse("P1", "{\"type\":\"groupCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000",
        "{\"type\":\"array\",\"items\":\"group\",\"customId\":10000}",
        "[{\"groupId\":\"G1\"}]");
    s.groupPages(
        JiraAccessScenario.page(0, 2, s.userJson("U2")),
        JiraAccessScenario.page(1, 2, s.userJson("U3")));
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.grantGroupIds()).contains("G1");
  }
}
