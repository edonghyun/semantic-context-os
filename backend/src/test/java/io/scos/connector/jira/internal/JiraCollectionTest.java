package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunStatus;
import io.scos.connector.jira.internal.JiraRuntimeScenario.ReportView;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Jira 수집의 DB 종단 계약(TESTING §3.6 6층 — 커넥터마다 다시 증명하는 것): 처음 수집한 원본·blob·체크포인트가 TS와 같고, 다시 수집해도 지우지
 * 않으며(삭제 감지 없음) 첨부를 다시 받지 않고, 공급자 쪽 편집은 그 원본의 버전만 바꾼다. 실행은 상황마다 한 번(규칙 6).
 */
@Tag("db")
@DisplayName("Jira 수집은 DB에 TS와 같이 남고 삭제를 추론하지 않는다(§3.6 6층)")
class JiraCollectionTest {
  static final JsonNode TS_FIRST = JiraFixtures.read("expected-raw-items.json");

  /** 둘째 수집에서 검색 응답에서 빼는 이슈. */
  static final String GONE_ISSUE_ID = "40002";

  static final String GONE_ISSUE = "issue:" + GONE_ISSUE_ID;

  static final String EDITED_SUMMARY = "결제 승인 지연 장애(원인 확정)";

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("TS 데이터셋을 처음 수집하면")
  class FirstCollection {
    JiraCollectionScenario jira;
    CollectionRuntime.Report report;
    Map<String, String> versions;
    long revision;
    Optional<Long> pngBlob;

    @BeforeAll
    void collectOnce() {
      jira = new JiraCollectionScenario();
      report = jira.collect();
      versions = jira.activeVersions();
      revision = jira.checkpointRevision();
      pngBlob = jira.storedBlobSize(JiraFixtures.PNG_SHA256);
    }

    @AfterAll
    void close() {
      jira.close();
    }

    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(TS_FIRST.get("report")));
    }

    @Test
    @DisplayName("active 원본과 버전이 TS와 같다")
    void versionsMatchTs() {
      // then
      assertThat(versions).isEqualTo(JiraRuntimeScenario.versionsOf(TS_FIRST.get("items")));
    }

    @Test
    @DisplayName("체크포인트는 페이지마다 한 번씩 올라 TS와 같은 revision이다")
    void revisionMatchesTs() {
      // then
      assertThat(revision).isEqualTo(TS_FIRST.get("checkpoint").get("revision").asLong());
    }

    @Test
    @DisplayName("첨부 원본 바이트가 blob으로 저장된다")
    void storesAttachmentBlob() {
      // then
      assertThat(pngBlob).contains((long) JiraFixtures.attachmentBytes("50002").length);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("같은 사이트를 다시 수집하면")
  class Recollection {
    JiraCollectionScenario jira;
    CollectionRuntime.Report second;
    long attachmentRequestsInSecondRun;

    @BeforeAll
    void collectTwice() {
      jira = new JiraCollectionScenario();
      jira.collect();
      long beforeSecond = jira.attachmentRequests();
      second = jira.collect();
      attachmentRequestsInSecondRun = jira.attachmentRequests() - beforeSecond;
    }

    @AfterAll
    void close() {
      jira.close();
    }

    @Test
    @DisplayName("바뀐 것이 없으면 원본을 바꾸지 않는다")
    void changesNothing() {
      // then
      assertThat(second)
          .extracting(CollectionRuntime.Report::upserted, CollectionRuntime.Report::deleted)
          .containsExactly(0, 0);
    }

    @Test
    @DisplayName("이미 받은 첨부는 다시 받지 않는다")
    void skipsDownloadedAttachments() {
      // then
      assertThat(attachmentRequestsInSecondRun).isZero();
    }
  }

  /**
   * TS spec "목록에서 사라진 리소스를 삭제로 추론하지 않는다"(CONTRACTS "Jira 커넥터 편차" — 매번 전체 순회인데 incremental 보고): 검색에서
   * 빠진 이슈도 active로 남고 tombstone이 생기지 않는다.
   */
  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("이슈 하나가 검색에서 사라진 뒤 다시 수집하면")
  class IssueGoneFromSearch {
    JiraCollectionScenario jira;
    boolean activeAfter;
    long tombstones;

    @BeforeAll
    void collectWithoutIssue() {
      jira = new JiraCollectionScenario();
      jira.collect();
      if (!jira.activeVersions().containsKey(GONE_ISSUE))
        throw new IllegalStateException("given 준비 실패: 첫 수집 뒤 " + GONE_ISSUE + "가 active여야 한다");
      jira.everyResourceReadable();
      jira.issueSearchOmits(GONE_ISSUE_ID);
      long goneIssueRequestsBefore = jira.issueRequests(GONE_ISSUE_ID);
      CollectionRuntime.Report second = jira.collect();
      // 검색에서 빼는 필터가 듣지 않으면 둘째 수집이 이슈를 다시 읽어 아래 부재 단언이 공짜로 통과한다
      if (jira.issueRequests(GONE_ISSUE_ID) != goneIssueRequestsBefore)
        throw new IllegalStateException("given 준비 실패: 둘째 수집이 검색에서 뺀 이슈 " + GONE_ISSUE_ID + "를 읽었다");
      // 거부 없이 끝난 run이어야 정리 대상이 된다 — 그래야 "남는다"가 정리 규칙의 관찰이다
      if (second.status() != RunStatus.SUCCEEDED)
        throw new IllegalStateException("given 준비 실패: 둘째 수집이 성공해야 한다 — " + second.status());
      activeAfter = jira.activeVersions().containsKey(GONE_ISSUE);
      tombstones = jira.tombstones();
    }

    @AfterAll
    void close() {
      jira.close();
    }

    @Test
    @DisplayName("사라진 이슈가 active로 남는다")
    void keepsGoneIssue() {
      // then
      assertThat(activeAfter).isTrue();
    }

    @Test
    @DisplayName("목록에 안 보인 것을 지우지 않는다(tombstone 0)")
    void leavesNoTombstones() {
      // then
      assertThat(tombstones).isZero();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("이슈 요약을 고친 뒤 다시 수집하면")
  class EditedIssue {
    JiraCollectionScenario jira;
    Map<String, String> before;
    Map<String, String> after;
    CollectionRuntime.Report second;

    @BeforeAll
    void collectEditCollect() {
      jira = new JiraCollectionScenario();
      jira.collect();
      before = jira.activeVersions();
      jira.issue40001SummaryEdited(EDITED_SUMMARY);
      second = jira.collect();
      after = jira.activeVersions();
    }

    @AfterAll
    void close() {
      jira.close();
    }

    @Test
    @DisplayName("고친 이슈의 버전이 바뀐다")
    void editedIssueGetsNewVersion() {
      // then
      assertThat(after.get("issue:40001")).isNotEqualTo(before.get("issue:40001"));
    }

    @Test
    @DisplayName("다른 원본의 버전은 그대로다")
    void othersKeepVersions() {
      // then: 고친 이슈를 뺀 첫 수집 버전이 그대로 있다
      Map<String, String> othersBefore = new HashMap<>(before);
      othersBefore.remove("issue:40001");
      assertThat(after).containsAllEntriesOf(othersBefore);
    }

    @Test
    @DisplayName("upsert는 고친 이슈 하나다")
    void upsertsOnlyEditedIssue() {
      // then
      assertThat(second.upserted()).isEqualTo(1);
    }
  }

  /** TS 장애 사례 "이슈 40002가 계속 503"(expected-failures.json) — 재시도를 다 쓰고 앞 페이지까지만 남는다. */
  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("이슈 하나가 계속 503이면")
  class PersistentServerError {
    final JsonNode ts = tsScenario("이슈 40002가 계속 503");
    JiraCollectionScenario jira;
    CollectionRuntime.Report report;
    String storedRun;
    JsonNode checkpoint;

    @BeforeAll
    void collectUnderFailure() {
      jira = new JiraCollectionScenario();
      jira.givenFailure(ts.get("failure"));
      report = jira.collect();
      storedRun = jira.latestRunStatus();
      checkpoint = jira.storedCheckpointOrNull();
    }

    @AfterAll
    void close() {
      jira.close();
    }

    @Test
    @DisplayName("run 보고가 TS와 같다(커밋한 페이지까지 partially_succeeded)")
    void reportMatchesTs() {
      // then
      assertThat(ReportView.of(report)).isEqualTo(ReportView.expected(ts.get("report")));
    }

    @Test
    @DisplayName("저장된 run은 TS처럼 dependency_unavailable로 남는다")
    void storedRunMatchesTs() {
      // then
      assertThat(storedRun)
          .isEqualTo(
              ts.get("run").get("status").asString()
                  + "/"
                  + ts.get("run").get("errorKind").asString());
    }

    @Test
    @DisplayName("저장된 체크포인트가 TS와 같다(커밋한 페이지까지)")
    void checkpointMatchesTs() {
      // then
      assertThat(checkpoint).isEqualTo(ts.get("checkpoint").get("checkpoint"));
    }
  }

  static JsonNode tsScenario(String name) {
    for (JsonNode s : JiraFixtures.read("expected-failures.json").get("scenarios"))
      if (s.get("failure").get("name").asString().equals(name)) return s;
    throw new IllegalStateException("TS 장애 사례 없음: " + name);
  }
}
