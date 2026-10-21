package io.scos.connector.jira.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.internal.JiraScenario.Collection;
import io.scos.connector.jira.internal.JiraScenario.Interrupted;
import io.scos.connector.jira.internal.JiraTsRuns.PageView;
import io.scos.connector.jira.internal.JiraTsRuns.TsRun;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallMetrics.CallOutcome;
import io.scos.engine.support.call.ProviderCallTags;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F5 동등성(TESTING §3.6 3층): 가짜 Jira(harness/fixtures/jira)에서 Java JiraAdapter가 낸 페이지·원본·체크포인트·거부·요청이
 * TS JiraAdapter와 같다. 작업 큐 체크포인트 v1, 페이지 중간 중단 뒤 재개, 배치 동시 4, 체크포인트 검증도 본다.
 */
@DisplayName("Jira 어댑터는 TS JiraAdapter와 같은 페이지·원본·체크포인트를 낸다(F5)")
class JiraAdapterFixtureTest {
  /** 첫 필드 요청의 Retry-After(초). */
  static final Duration FIELDS_RETRY_AFTER = Duration.ofSeconds(1);

  /** TS MAX_CONCURRENT_READS. */
  static final int CONCURRENT_READS = 4;

  static final String PROJECT_SEARCH = "/rest/api/3/project/search";

  /** 마지막 페이지(빈 목록). */
  static final String EMPTY_LAST_PAGE = "{\"values\":[],\"isLast\":true}";

  /** 공급자 호출 지표의 조작 이름(운영 대시보드가 이 이름으로 묶는다). */
  static final String READ = "jira.read";

  static final String ATTACHMENT_READ = "jira.attachment";

  /** 데이터셋의 429 응답 수(필드 목록 Retry-After 초, 40002 원격 링크 Retry-After HTTP-date) — 각각 한 번 재시도한다. */
  static final int DATASET_RATE_LIMITS = 2;

  /** 공급자 호출 시도 상한(ProviderCallPolicy 기본). */
  static final int ATTEMPTS = 5;

  /** 일시 장애(재시도 대상). */
  static final int TRANSIENT = 503;

  static final String ATTACHMENT_CONTENT = "/rest/api/3/attachment/content/";

  /** 데이터셋 PNG 첨부 작업 하나(이슈 PAY-4, 16바이트). */
  static final String PNG_ATTACHMENT_WORK =
      "[{\"kind\":\"attachment\",\"id\":\"50002\",\"key\":\"PAY-4\",\"size\":16}]";

  /** 권한 없음 — 선택 목록이면 거부, 필수 목록이면 실패. */
  static final int FORBIDDEN = 403;

  /** 연달아 있는 이슈 다섯 — 첫 배치가 동시 상한보다 크다. */
  static final String FIVE_ISSUES =
      "[{\"kind\":\"issue\",\"id\":\"40001\"},{\"kind\":\"issue\",\"id\":\"40002\"},"
          + "{\"kind\":\"issue\",\"id\":\"40003\"},{\"kind\":\"issue\",\"id\":\"40006\"},"
          + "{\"kind\":\"issue\",\"id\":\"40007\"}]";

  /** FIVE_ISSUES의 둘째 이슈 — 첫 배치(동시 4)가 기다리는 요청 하나. */
  static final String SECOND_OF_FIVE = "/rest/api/3/issue/40002";

  static final String TWO_ISSUES =
      "[{\"kind\":\"issue\",\"id\":\"40001\"},{\"kind\":\"issue\",\"id\":\"40002\"}]";

  final JiraScenario jira = new JiraScenario();

  @AfterEach
  void tearDown() {
    jira.close();
  }

  @Nested
  @DisplayName("처음부터 수집하면 TS와 같다")
  class MatchesTs {
    final TsRun ts = TsRun.firstCollection();

    @Test
    @DisplayName("페이지마다 같은 원본을 같은 순서로 낸다")
    void pagesUpsertSameItems() {
      // when
      Collection java = jira.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::upserted)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::upserted).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 작업 큐 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = jira.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 거부를 낸다")
    void pagesCarrySameRejections() {
      // when
      Collection java = jira.collect();

      // then
      assertThat(java.pageViews())
          .extracting(PageView::rejections)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::rejections).toList());
    }

    @Test
    @DisplayName("원본 항목의 필드가 같다(버전·시각·메타데이터·본문·blob)")
    void itemsMatch() {
      // when
      Collection java = jira.collect();

      // then
      assertThat(java.lastItems()).containsExactlyInAnyOrderElementsOf(ts.items());
    }

    @Test
    @DisplayName("같은 요청을 보낸다(배치 안 순서는 무관)")
    void sendsSameRequests() {
      // when
      jira.collect();

      // then
      assertThat(jira.requests()).containsExactlyInAnyOrderElementsOf(ts.requests());
    }

    @Test
    @DisplayName("필드 요청 제한은 Retry-After만큼 기다린다")
    void waitsRetryAfter() {
      // when
      jira.collect();

      // then
      assertThat(jira.waits()).contains(FIELDS_RETRY_AFTER);
    }

    @Test
    @DisplayName("요청 제한 재시도는 jira.read 재시도 지표로 남는다")
    void rateLimitedRetryIsCounted() {
      // when
      jira.collect();

      // then
      assertThatMetrics(jira.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS,
              ProviderCallTags.of(READ, CallOutcome.RETRY, Optional.of(Kind.RATE_LIMITED)),
              DATASET_RATE_LIMITS);
    }
  }

  @Nested
  @DisplayName("중단된 run의 체크포인트에서 재개하면 TS와 같다")
  class Resume {
    final TsRun ts = TsRun.resumedAfterCrash();

    @Test
    @DisplayName("페이지마다 같은 거부를 낸다")
    void pagesCarrySameRejections() {
      // when
      Collection java = jira.collectFrom(TsRun.crashCheckpoint());

      // then
      assertThat(java.pageViews())
          .extracting(PageView::rejections)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::rejections).toList());
    }

    @Test
    @DisplayName("남은 작업부터 같은 원본을 같은 순서로 낸다")
    void pagesUpsertSameItems() {
      // when
      Collection java = jira.collectFrom(TsRun.crashCheckpoint());

      // then
      assertThat(java.pageViews())
          .extracting(PageView::upserted)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::upserted).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = jira.collectFrom(TsRun.crashCheckpoint());

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("이미 확정된 작업은 다시 요청하지 않는다")
    void sendsOnlyRemainingRequests() {
      // when
      jira.collectFrom(TsRun.crashCheckpoint());

      // then
      assertThat(jira.requests()).containsExactlyInAnyOrderElementsOf(ts.requests());
    }

    @Test
    @DisplayName("재개한 run이 낸 원본은 TS 재개 run이 낸 원본과 같다")
    void itemsMatchTsResumedRun() {
      // when
      Collection java = jira.collectFrom(TsRun.crashCheckpoint());

      // then
      assertThat(java.lastItems()).containsExactlyInAnyOrderElementsOf(ts.upsertedItems());
    }
  }

  @Nested
  @DisplayName("저장된 체크포인트에서 시작하면")
  class FromStoredCheckpoint {
    @ParameterizedTest(name = "start {0} → startAt={1}")
    @CsvSource({
      "1.0, 1",
      "1e3, 1000",
      "3000000000, 3000000000",
      "9007199254740991, 9007199254740991"
    })
    @DisplayName("TS가 받는 시작 위치(안전 정수)는 그 값으로 요청한다")
    void requestsSafeIntegerStart(String start, String startAt) {
      // given
      jira.issueAnswers(PROJECT_SEARCH, EMPTY_LAST_PAGE);

      // when
      jira.collectFrom(
          JiraScenario.checkpointWith("[{\"kind\":\"projects\",\"start\":" + start + "}]", "[]"));

      // then
      assertThat(jira.requests().getFirst().params()).containsEntry("startAt", startAt);
    }

    @Test
    @DisplayName("시작 위치 0인 작업은 유효한 체크포인트다")
    void acceptsZeroStart() {
      // when
      Collection java =
          jira.collectFrom(
              JiraScenario.checkpointWith("[{\"kind\":\"dashboards\",\"start\":0}]", "[]"));

      // then
      assertThat(java.pageViews())
          .extracting(PageView::upserted)
          .containsExactly(List.of("dashboard:10200"));
    }

    @Test
    @DisplayName("작업이 다 끝난 체크포인트면 사이트 전체를 다시 시작한다")
    void restartsFromRootsWhenNothingPending() {
      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith("[]", "[\"50001\"]"));

      // then
      assertThat(java.pageViews().getFirst().upserted())
          .containsExactly("field:summary", "field:customfield_10010");
    }
  }

  /**
   * 프로젝트·필드·이슈 검색은 필수 목록이다: 읽을 수 없으면 커버리지가 조용히 빠지지 않게 run을 실패시킨다. 그 밖의 목록(보드·컴포넌트 등)의 403은 거부로 남기고
   * 계속한다 — 데이터셋의 OPS 컴포넌트 403("처음부터 수집하면 TS와 같다").
   */
  @Nested
  @DisplayName("필수 목록을 읽을 권한이 없으면")
  class RequiredListingForbidden {
    static Stream<Arguments> requiredListings() {
      return Stream.of(
          Arguments.of("프로젝트", "{\"kind\":\"projects\"}", "/rest/api/3/project/search"),
          Arguments.of("필드", "{\"kind\":\"fields\"}", "/rest/api/3/field"),
          Arguments.of(
              "이슈 검색", "{\"kind\":\"issues\",\"id\":\"10000\"}", "/rest/api/3/search/jql"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredListings")
    @DisplayName("permission_denied로 실패한다")
    void failsAsPermissionDenied(String label, String work, String path) {
      // given
      jira.pathAnswers(path, FORBIDDEN);

      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith("[" + work + "]", "[]"));

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.PERMISSION_DENIED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredListings")
    @DisplayName("거부로 남기지 않는다")
    void leavesNoRejection(String label, String work, String path) {
      // given
      jira.pathAnswers(path, FORBIDDEN);

      // when
      Collection java =
          jira.collectFrom(JiraScenario.checkpointWith("[" + work + "]", "[]")).requireFailed();

      // then
      assertThat(java.rejections()).isEmpty();
    }
  }

  @Nested
  @DisplayName("체크포인트가 TS v1 계약을 어기면")
  class InvalidCheckpoint {
    /** 이 사이트의 체크포인트 값(siteUrl 뒤에 이 필드들). */
    static String onSite(String fields) {
      return "{\"siteUrl\":\"" + JiraScenario.SITE + "\"," + fields + "}";
    }

    static Checkpoint v1(String fields) {
      return new Checkpoint(1, onSite(fields));
    }

    static Stream<Arguments> invalidCheckpoints() {
      return Stream.of(
          Arguments.of(
              "다른 사이트",
              new Checkpoint(1, "{\"siteUrl\":\"https://other.atlassian.net\",\"pending\":[]}"),
              "Invalid Jira checkpoint"),
          Arguments.of(
              "스키마 2", new Checkpoint(2, onSite("\"pending\":[]")), "Invalid Jira checkpoint"),
          Arguments.of("pending이 배열이 아님", v1("\"pending\":{}"), "Invalid Jira checkpoint"),
          Arguments.of(
              "모르는 작업 종류",
              v1("\"pending\":[{\"kind\":\"users\"}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "음수 시작 위치",
              v1("\"pending\":[{\"kind\":\"projects\",\"start\":-1}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "안전 정수를 넘는 시작 위치",
              v1("\"pending\":[{\"kind\":\"projects\",\"start\":9007199254740992}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "문자열 시작 위치",
              v1("\"pending\":[{\"kind\":\"projects\",\"start\":\"1\"}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "소수 시작 위치",
              v1("\"pending\":[{\"kind\":\"projects\",\"start\":1.5}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "빈 id",
              v1("\"pending\":[{\"kind\":\"issue\",\"id\":\"\"}]"),
              "Invalid Jira checkpoint work"),
          Arguments.of(
              "루트 밖 작업에 id 없음",
              v1("\"pending\":[{\"kind\":\"issue\"}]"),
              "Missing Jira checkpoint identity"),
          Arguments.of(
              "첨부 이력에 문자열 아님",
              v1("\"pending\":[],\"downloadedAttachments\":[9]"),
              "Invalid Jira attachment checkpoint"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCheckpoints")
    @DisplayName("TS와 같은 메시지로 실패한다")
    void failsWithTsMessage(String label, Checkpoint checkpoint, String message) {
      // when
      Collection java = jira.collectFrom(checkpoint);

      // then
      assertThat(java.failure())
          .hasValueSatisfying(
              f -> assertThat(f).isInstanceOf(JiraProtocolException.class).hasMessage(message));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCheckpoints")
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest(String label, Checkpoint checkpoint, String message) {
      // when
      jira.collectFrom(checkpoint).requireFailed();

      // then
      assertThat(jira.requests()).isEmpty();
    }

    @Test
    @DisplayName("run에는 TS처럼 errorKind Error로 남는다")
    void protocolViolationRecordsTsErrorKind() {
      // when
      Collection java =
          jira.collectFrom(
              new Checkpoint(2, "{\"siteUrl\":\"" + JiraScenario.SITE + "\",\"pending\":[]}"));

      // then
      assertThat(java.failure())
          .hasValueSatisfying(
              f ->
                  assertThat(f)
                      .isInstanceOfSatisfying(
                          JiraProtocolException.class,
                          e -> assertThat(e.runErrorKind()).isEqualTo("Error")));
    }
  }

  @Nested
  @DisplayName("소스 종류와 coverage를 물으면")
  class Identity {
    @Test
    @DisplayName("kind는 jira다")
    void kindIsJira() {
      // when
      String kind = jira.kind();

      // then
      assertThat(kind).isEqualTo("jira");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CollectionMode.class)
    @DisplayName("목록에서 사라진 것을 삭제로 볼 수 없어 늘 incremental로 보고한다")
    void reportsIncrementalCoverage(CollectionMode mode) {
      // when
      CollectionCoverage coverage = jira.coverage(mode);

      // then
      assertThat(coverage).isEqualTo(CollectionCoverage.INCREMENTAL);
    }
  }

  @Nested
  @DisplayName("남은 이슈가 동시 상한(4)보다 많으면")
  class MoreIssuesThanConcurrency {
    @Test
    @DisplayName("이슈는 동시에 넷까지만 읽는다")
    void readsAtMostFourIssuesAtOnce() {
      // given
      jira.pathAnswersWhenInFlight("/rest/api/3/issue/", CONCURRENT_READS);

      // when
      jira.collectFrom(JiraScenario.checkpointWith(FIVE_ISSUES, "[]"));

      // then
      assertThat(jira.maxInFlight()).isEqualTo(CONCURRENT_READS);
    }
  }

  @Nested
  @DisplayName("배치의 한 작업이 일시 장애로 끝나면")
  class BatchFailure {
    @BeforeEach
    void givenSecondIssueUnavailable() {
      jira.pathAnswers("/rest/api/3/issue/40002", TRANSIENT);
    }

    @Test
    @DisplayName("성공한 형제 작업도 페이지로 확정하지 않는다")
    void commitsNothing() {
      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(TWO_ISSUES, "[]"));

      // then
      assertThat(java.pages()).isEmpty();
    }

    @Test
    @DisplayName("dependency_unavailable로 실패한다")
    void failsAsDependencyUnavailable() {
      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(TWO_ISSUES, "[]"));

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.DEPENDENCY_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("PNG 첨부 내용이 시도 상한까지 5xx면")
  class AttachmentUnavailable {
    @Test
    @DisplayName("jira.attachment 실패 지표로 남는다")
    void exhaustedAttachmentRetriesAreCounted() {
      // given
      jira.failNext(ATTACHMENT_CONTENT, ATTEMPTS, TRANSIENT);

      // when
      jira.collectFrom(JiraScenario.checkpointWith(PNG_ATTACHMENT_WORK, "[]"));

      // then
      assertThatMetrics(jira.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS,
              ProviderCallTags.of(
                  ATTACHMENT_READ, CallOutcome.FAILED, Optional.of(Kind.DEPENDENCY_UNAVAILABLE)),
              1);
    }
  }

  /**
   * 관찰 계약만 고정한다: 배치 루프의 취소 검사(TS throwIfAborted와 짝)는 실행기 취소와 겹치는 방어라, 그 줄을 지워도 실행기가 먼저 취소를 보고 같은
   * 결과가 난다(F5 리뷰 m9·n3).
   */
  @Nested
  @DisplayName("시작 전에 취소되면")
  class Cancelled {
    @BeforeEach
    void givenCancelledBeforeStart() {
      jira.cancelBeforeStart();
    }

    @Test
    @DisplayName("cancelled로 실패한다")
    void failsAsCancelled() {
      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(FIVE_ISSUES, "[]"));

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest() {
      // when
      jira.collectFrom(JiraScenario.checkpointWith(FIVE_ISSUES, "[]")).requireFailed();

      // then
      assertThat(jira.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("배치를 기다리는 중 수집 스레드가 인터럽트되면")
  class InterruptedWhileAwaitingBatch {
    Interrupted collectInterrupted() {
      return jira.collectInterruptedOnRequest(
          SECOND_OF_FIVE, JiraScenario.checkpointWith(FIVE_ISSUES, "[]"));
    }

    @Test
    @DisplayName("cancelled로 실패한다")
    void failsAsCancelled() {
      // when
      Interrupted run = collectInterrupted();

      // then
      assertThat(run.collection().failureKind()).contains(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("인터럽트 표시를 지우지 않는다")
    void keepsInterruptFlag() {
      // when
      Interrupted run = collectInterrupted();

      // then
      assertThat(run.stillInterrupted()).isTrue();
    }
  }

  /** 헤더(Basic)에 실을 수 없는 자격 증명 — CRLF .env·여러 줄 파일에서 온 값. */
  @Nested
  @DisplayName("자격 증명 값에 줄바꿈이 있으면")
  class CredentialWithLineBreak {
    @BeforeEach
    void givenCredentialWithCarriageReturn() {
      jira.credentialResolvesTo(jira.token() + "\r");
    }

    @Test
    @DisplayName("invalid_configuration으로 실패한다")
    void failsAsInvalidConfiguration() {
      // when
      Collection java = jira.collect();

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest() {
      // when
      jira.collect().requireFailed();

      // then
      assertThat(jira.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("이슈 응답의 식별자가 TS와 다른 모양이면")
  class IssueIdentity {
    static final String ISSUE = "/rest/api/3/issue/40001";
    static final String ONE_ISSUE = "[{\"kind\":\"issue\",\"id\":\"40001\"}]";

    /** 내장 자식 목록이 모두 완전한(빈) 이슈 본문 — 끝점을 더 부르지 않는다. */
    static final String EMPTY_CHILDREN =
        "\"fields\":{\"comment\":{\"total\":0,\"comments\":[]},\"worklog\":{\"total\":0,\"worklogs\":[]}},"
            + "\"changelog\":{\"total\":0,\"histories\":[]}";

    static final String ISSUE_WITH_NUMERIC_ID =
        "{\"id\":40001,\"key\":\"PAY-1\"," + EMPTY_CHILDREN + "}";

    static final String ISSUE_WITHOUT_KEY = "{\"id\":\"40001\"," + EMPTY_CHILDREN + "}";

    static final String ISSUE_WITHOUT_ID = "{\"key\":\"PAY-1\",\"fields\":{}}";

    @Test
    @DisplayName("id가 숫자여도 같은 이슈로 본다(TS String(id))")
    void acceptsNumericId() {
      // given
      jira.issueAnswers(ISSUE, ISSUE_WITH_NUMERIC_ID);

      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(ONE_ISSUE, "[]"));

      // then
      assertThat(java.pageViews())
          .extracting(PageView::upserted)
          .containsExactly(List.of("issue:40001"));
    }

    @Test
    @DisplayName("key가 없으면 TS String(undefined)처럼 url이 /browse/undefined다")
    void missingKeyBrowsesUndefined() {
      // given
      jira.issueAnswers(ISSUE, ISSUE_WITHOUT_KEY);

      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(ONE_ISSUE, "[]"));

      // then
      assertThat(
              JiraFixtures.JSON
                  .readTree(java.lastItems().getFirst().metadataJson())
                  .get("url")
                  .asString())
          .isEqualTo(JiraScenario.SITE + "/browse/undefined");
    }

    @Test
    @DisplayName("id가 없으면 Error로 실패한다")
    void failsWithoutId() {
      // given
      jira.issueAnswers(ISSUE, ISSUE_WITHOUT_ID);

      // when
      Collection java = jira.collectFrom(JiraScenario.checkpointWith(ONE_ISSUE, "[]"));

      // then
      assertThat(java.failure())
          .hasValueSatisfying(
              f ->
                  assertThat(f)
                      .isInstanceOf(JiraProtocolException.class)
                      .hasMessage("Jira returned wrong issue identity"));
    }
  }
}
