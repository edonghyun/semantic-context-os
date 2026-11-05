package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira 접근 매핑은 필드 모양·중단·동적 신원의 경계를 지킨다 (T-b17)")
class JiraAccessEdgeTest {
  private final JiraAccessScenario s = new JiraAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("Browse에 명시된 담당자는 직접 키를 얻는다")
  void explicitlyGrantedAssigneeGetsUserKey() {
    // given
    s.browse("P1", "{\"type\":\"assignee\"}");
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).contains("user:jira-a:U2");
    assertThat(s.identityIds()).containsExactly("U2");
  }

  @Test
  @DisplayName("보고자가 명시 null이면 직접 키가 없다")
  void nullReporterDoesNotAddUserKey() {
    // given
    s.browse("P1", "{\"type\":\"reporter\"}");
    s.issueField("reporter", "null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001"))
        .containsExactly(
            "group:jira-a:~project%3AP1%3A4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945");
  }

  @Test
  @DisplayName("게스트 보고자는 직접 키를 얻지 않는다")
  void guestReporterDoesNotAddUserKey() {
    // given
    s.browse("P1", "{\"type\":\"reporter\"}");
    s.userResponse("U1", s.customerUser("U1"));
    s.issue("10001", "P1", ",\"security\":null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001")).doesNotContain("user:jira-a:U1");
  }

  @Test
  @DisplayName("null custom field는 직접 사용자 키를 만들지 않는다")
  void nullCustomFieldIsEmpty() {
    // given
    s.browse("P1", "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue("customfield_10000", "{\"type\":\"user\",\"customId\":10000}", "null");
    // when
    s.collect("issue:10001");
    // then
    assertThat(s.keys("issue:10001"))
        .containsExactly(
            "group:jira-a:~project%3AP1%3A4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"type\":\"group\",\"customId\":10000}",
        "{\"type\":\"array\",\"items\":\"group\",\"customId\":10000}"
      })
  @DisplayName("user holder와 다른 schema 유형은 FAILED다")
  void customFieldTypeMustMatchHolder(String schema) {
    // given
    s.browse("P1", "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue("customfield_10000", schema, "{\"accountId\":\"U3\"}");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @Test
  @DisplayName("array schema에 단일 객체가 오면 FAILED다")
  void arraySchemaRequiresArrayValue() {
    // given
    s.browse("P1", "{\"type\":\"userCustomField\",\"parameter\":\"customfield_10000\"}");
    s.customIssue(
        "customfield_10000",
        "{\"type\":\"array\",\"items\":\"user\",\"customId\":10000}",
        "{\"accountId\":\"U3\"}");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @Test
  @DisplayName("관측하지 못한 보안 수준은 FAILED다")
  void unobservedSecurityLevelIsFailed() {
    // given
    s.issue("10001", "P1", ",\"security\":{\"id\":\"L9\"}");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "issue:", "unknown:1", "a:1"})
  @DisplayName("잘못된 carrier도 FAILED로 내고 미완료다")
  void invalidCarrierIsFailed(String id) {
    // when
    var failure = catchThrowable(() -> s.collect(id));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl(id).status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @Test
  @DisplayName("선행 취소는 정책 차단 대상도 처리하지 않는다")
  void cancellationStopsBeforeTarget() {
    // given
    s.cancel();
    // when
    var failure = catchThrowable(() -> s.collect("filter:1"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.requestsTo("/project/P1")).isZero();
    assertThat(s.observedIds()).isEmpty();
  }

  @Test
  @DisplayName("UNKNOWN 프로젝트의 이슈는 FAILED로 닫힌다")
  void unknownProjectStyleIssueIsFailed() {
    // given
    s.unknownProject("P1");
    s.issue("10001", "P1", ",\"security\":null");
    // when
    var failure = catchThrowable(() -> s.collect("issue:10001"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl("issue:10001").status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "issue:10001| /issue/10001?fields=project,security,reporter,assignee&expand=schema",
        "component:10| /component/10",
        "version:20| /version/20"
      },
      delimiter = '|')
  @DisplayName("응답 식별자가 요청과 다르면 FAILED다")
  void conflictingResourceIdIsFailed(String target, String path) {
    // given
    s.answer(
        path,
        "{\"id\":\"different\",\"projectId\":\"P1\",\"fields\":{\"project\":{\"id\":\"P1\"},\"security\":null}}");
    // when
    var failure = catchThrowable(() -> s.collect(target));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.acl(target).status()).isEqualTo(DocumentAcl.Status.FAILED);
  }

  @Test
  @DisplayName("첫 페이지 뒤 취소하면 후속 정책 차단 페이지도 보내지 않는다")
  void cancellationAfterPageStopsLaterTargets() {
    // given
    s.cancelAfterPage();
    // when
    var failure = catchThrowable(() -> s.collect("filter:1", "filter:2"));
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
    assertThat(s.observedIds()).containsExactly("filter:1");
  }

  @Test
  @DisplayName("마지막 페이지 뒤 인터럽트도 완료로 반환하지 않는다")
  void interruptAfterLastPagePreventsCompletion() {
    // when
    var failure = catchThrowable(s::collectInterruptedOnLastPage);
    // then
    assertThat(failure).isInstanceOf(CallFailureException.class);
  }
}
