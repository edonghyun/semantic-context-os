package io.scos.collection.internal.projection;

import static io.scos.collection.internal.projection.ProjectionScenario.FIRST_BACKOFF;
import static io.scos.collection.internal.projection.ProjectionScenario.LEASE_TTL;
import static io.scos.collection.internal.projection.ProjectionScenario.MAIN_CHANNEL;
import static io.scos.collection.internal.projection.ProjectionScenario.SENTINEL;
import static io.scos.collection.internal.projection.ProjectionScenario.SOURCE;
import static io.scos.collection.internal.projection.ProjectionScenario.THREAD_DOCUMENT;
import static io.scos.collection.internal.projection.ProjectionScenario.expectedUnitContents;
import static io.scos.collection.internal.projection.ProjectionScenario.expectedUnitIds;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.scos.collection.internal.OutboxPort;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.ContextUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 수집 → 투영 종단(ADR-0018 §7): 가짜 Slack → 원본 → 투영 유닛이 TS 기대(expected-units.json)와 같고 검색 가능하다. 수정·삭제가
 * 유닛에 반영되고, 투영 실패는 요청 행을 남겨(attempts·backoff) 재처리하며 중복 0이다. 상황마다 DB 실행을 @BeforeAll에서 한 번 하고 기록을 계약별
 * 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@DisplayName("수집 뒤 투영은 TS와 같은 유닛을 쓰고 실패는 요청 행으로 재처리한다(ADR-0018 §7)")
class ProjectionRunTest {
  /** 상황마다 시나리오 하나 — 실행·기록만 하고, 닫을 때 가짜 Slack의 미등록 요청을 확인한다. */
  abstract static class Situation {
    final ProjectionScenario projection = new ProjectionScenario();

    @AfterAll
    void close() {
      projection.close();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("수집 뒤 첫 투영이 끝나면")
  class AfterFirstProjection extends Situation {
    ProjectionRun.Report report;

    @BeforeAll
    void collectAndProject() {
      projection.collect();
      report = projection.project();
    }

    @Test
    @DisplayName("투영은 문서 3건으로 성공한다")
    void succeedsWithThreeDocuments() {
      // then
      assertThat(report)
          .extracting(ProjectionRun.Report::outcome, ProjectionRun.Report::documents)
          .containsExactly(ProjectionRun.Outcome.SUCCEEDED, 3);
    }

    @Test
    @DisplayName("유닛 id가 TS 기대와 같다")
    void unitIdsMatchTs() {
      // then
      assertThat(projection.unitIds()).containsExactlyElementsOf(expectedUnitIds());
    }

    @Test
    @DisplayName("유닛 본문이 TS 기대와 같다")
    void unitContentsMatchTs() {
      // then
      assertThat(projection.units())
          .extracting(ContextUnit::content)
          .containsExactlyElementsOf(expectedUnitContents());
    }

    @Test
    @DisplayName("요청 행은 완료된다")
    void requestCompleted() {
      // then
      assertThat(projection.pending()).isEmpty();
    }

    @Test
    @DisplayName("유닛이 키워드 색인에 있다")
    void unitsAreKeywordIndexed() {
      // then
      assertThat(projection.hasKeywordHits("커넥션 풀 고갈")).isTrue();
    }

    @Test
    @DisplayName("유닛마다 벡터가 있다")
    void unitsAreVectorIndexed() {
      // then
      assertThat(projection.vectors()).hasSize(3);
    }

    @Test
    @DisplayName("검색 레시피에서 결제 장애 원인은 스레드 문서가 1위다")
    void searchFindsThread() {
      // then
      assertThat(projection.topDocumentFor("결제 장애 원인")).contains(THREAD_DOCUMENT);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("바뀐 것 없는 스냅샷 재수집 뒤")
  class UnchangedSnapshot extends Situation {
    List<ProjectionRun.Report> dueAfterSnapshot;

    @BeforeAll
    void recollect() {
      projection.collect();
      projection.project();
      projection.collectSnapshot();
      dueAfterSnapshot = projection.runDue();
    }

    @Test
    @DisplayName("투영 요청이 생기지 않는다")
    void requestsNoProjection() {
      // then
      assertThat(dueAfterSnapshot).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("투영 요청이 다시 들어왔지만 바뀐 것이 없으면(G5)")
  class UnchangedReprojection extends Situation {
    int callsBefore;
    ProjectionRun.Report reprojected;

    @BeforeAll
    void reproject() {
      projection.collect();
      projection.project();
      callsBefore = projection.embedCalls();
      projection.requestProjection();
      // project()는 투영이 정확히 한 건 돌지 않으면 멈춘다 — 투영이 실제로 돈 위에서 "재임베딩 0"을 본다
      reprojected = projection.project();
    }

    @Test
    @DisplayName("투영은 문서 3건으로 성공한다")
    void projectsThreeDocuments() {
      // then
      assertThat(reprojected)
          .extracting(ProjectionRun.Report::outcome, ProjectionRun.Report::documents)
          .containsExactly(ProjectionRun.Outcome.SUCCEEDED, 3);
    }

    @Test
    @DisplayName("임베딩을 호출하지 않는다")
    void embedsNothing() {
      // then
      assertThat(projection.embedCalls()).isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("유닛 수가 그대로다")
    void keepsUnits() {
      // then
      assertThat(projection.units()).hasSize(3);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("메시지가 편집되면")
  class AfterEdit extends Situation {
    static final String EDITED_TEXT = "배포 장애 회고(수정됨)";

    ProjectionRun.Report report;

    @BeforeAll
    void editAndReproject() {
      projection.collect();
      projection.project();
      projection.slackMessageEdited("1760400300.000000", EDITED_TEXT, "1760400500.000000");
      projection.collectSnapshot();
      report = projection.project();
    }

    @Test
    @DisplayName("재투영은 성공한다")
    void succeeds() {
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("유닛 id는 그대로다")
    void keepsUnitIds() {
      // then
      assertThat(projection.unitIds()).containsExactlyElementsOf(expectedUnitIds());
    }

    @Test
    @DisplayName("같은 유닛의 본문이 바뀐다")
    void updatesContent() {
      // then
      assertThat(projection.units().get(0).content()).contains(EDITED_TEXT);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("거부 항목과 스레드 메시지가 스냅샷에서 사라지면")
  class AfterDeletes extends Situation {
    int deleted;
    ProjectionRun.Report report;

    @BeforeAll
    void deleteAndReproject() {
      projection.collect();
      projection.project();
      projection.slackMessageEdited("1760400300.000000", "배포 장애 회고(수정됨)", "1760400500.000000");
      projection.collectSnapshot();
      projection.project();
      projection.rejectedSlackItemDisappears();
      projection.slackMessageDeleted(MAIN_CHANNEL, "1760400120.000000");
      projection.slackMessageDeleted(MAIN_CHANNEL, "1760400130.000000");
      deleted = projection.collectSnapshot().deleted();
      report = projection.project();
    }

    @Test
    @DisplayName("성공한 스냅샷이 두 항목을 삭제로 기록한다")
    void snapshotRecordsDeletes() {
      // then
      assertThat(deleted).isEqualTo(2);
    }

    @Test
    @DisplayName("재투영은 성공한다")
    void succeeds() {
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("사라진 스레드 메시지는 유닛 본문에서도 사라진다")
    void prunesThreadContent() {
      // then
      assertThat(projection.units())
          .filteredOn(u -> u.documentId().contains("thread"))
          .first()
          .extracting(ContextUnit::content)
          .asString()
          .doesNotContain("DB 커넥션 풀");
    }

    @Test
    @DisplayName("유닛은 중복 없이 3개다")
    void noDuplicates() {
      // then
      assertThat(projection.unitIds()).hasSize(3).doesNotHaveDuplicates();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("임베더가 실패해 투영이 실패하면")
  class AfterFailedProjection extends Situation {
    Instant failedAt;
    ProjectionRun.Report failed;
    OutboxPort.Request request;
    List<ContextUnit> unitsAfterFailure;
    List<ProjectionRun.Report> beforeBackoff;

    @BeforeAll
    void failOnce() {
      projection.collect();
      projection.embedderFails();
      failedAt = projection.now();
      failed = projection.project();
      request = projection.request().orElseThrow();
      unitsAfterFailure = projection.units();
      beforeBackoff = projection.runDue();
    }

    @Test
    @DisplayName("투영 결과는 FAILED다")
    void outcomeIsFailed() {
      // then
      assertThat(failed.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    }

    @Test
    @DisplayName("문서 3건마다 rate_limited 실패를 남긴다")
    void recordsFailurePerDocument() {
      // then
      assertThat(failed.documentFailures())
          .hasSize(3)
          .extracting(ProjectionRun.DocumentFailure::reason)
          .containsOnly("rate_limited");
    }

    @Test
    @DisplayName("실패한 문서는 sha256 참조로 남는다")
    void failureRefersToDocumentHash() {
      // then
      assertThat(failed.documentFailures().get(0).documentRef()).hasSize(64);
    }

    @Test
    @DisplayName("요청은 attempts 1로 남는다")
    void requestKeptWithOneAttempt() {
      // then
      assertThat(request.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청은 첫 backoff 뒤로 미뤄진다")
    void requestBacksOff() {
      // then
      assertThat(request.nextAttemptAt()).contains(failedAt.plus(FIRST_BACKOFF));
    }

    @Test
    @DisplayName("유닛을 쓰지 않는다")
    void writesNoUnits() {
      // then
      assertThat(unitsAfterFailure).isEmpty();
    }

    @Test
    @DisplayName("backoff 전에는 다시 시도하지 않는다")
    void notDueBeforeBackoff() {
      // then
      assertThat(beforeBackoff).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("임베더 실패로 실패한 투영을 backoff 뒤 재시도하면")
  class RetryAfterBackoff extends Situation {
    ProjectionRun.Report retried;

    @BeforeAll
    void failThenRetry() {
      projection.collect();
      projection.embedderFails();
      projection.project();
      projection.embedderRecovers();
      projection.advanceClock(FIRST_BACKOFF);
      retried = projection.project();
    }

    @Test
    @DisplayName("재시도는 성공한다")
    void retrySucceeds() {
      // then
      assertThat(retried.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("재시도 뒤 유닛은 중복 없이 TS 기대와 같다")
    void retryWritesUnitsOnce() {
      // then
      assertThat(projection.unitIds()).containsExactlyElementsOf(expectedUnitIds());
    }

    @Test
    @DisplayName("재시도 뒤 요청 행은 완료된다")
    void requestCompletedAfterRetry() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  /** 스레드 문서에만 있는 말 — 이 입력의 임베딩만 실패시킨다. */
  static final String THREAD_ONLY_TEXT = "결제 장애 원인 공유";

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("문서 3건 중 스레드 문서의 임베딩만 실패하면")
  class AfterPartialFailure extends Situation {
    ProjectionRun.Report failed;
    List<String> documentsAfterFailure;
    Optional<OutboxPort.Request> request;

    @BeforeAll
    void failOneDocument() {
      projection.collect();
      projection.embedderFailsOnInputContaining(THREAD_ONLY_TEXT);
      failed = projection.project();
      documentsAfterFailure = projection.units().stream().map(ContextUnit::documentId).toList();
      request = projection.request();
    }

    @Test
    @DisplayName("투영 결과는 FAILED다")
    void outcomeIsFailed() {
      // then
      assertThat(failed.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    }

    @Test
    @DisplayName("보고는 성공한 문서 2건·유닛 2개를 센다")
    void countsSucceededDocuments() {
      // then
      assertThat(failed)
          .extracting(ProjectionRun.Report::documents, ProjectionRun.Report::units)
          .containsExactly(2, 2);
    }

    @Test
    @DisplayName("스레드 문서 하나만 rate_limited 실패로 남긴다")
    void recordsOnlyThreadFailure() {
      // then: documentRef = sha256("slack://slack-synthetic/" + THREAD_DOCUMENT)
      assertThat(failed.documentFailures())
          .singleElement()
          .extracting(
              ProjectionRun.DocumentFailure::documentRef, ProjectionRun.DocumentFailure::reason)
          .containsExactly(
              "df2390450795847779e4bb6fc5f9f98854ad72a05497e7c7ed7486d0f32305a5", "rate_limited");
    }

    @Test
    @DisplayName("성공한 두 문서의 유닛은 쓰인다")
    void writesSucceededDocuments() {
      // then
      assertThat(documentsAfterFailure)
          .containsExactlyInAnyOrder("C0000000001/day-2025-10-14", "C0000000002/day-2025-10-15");
    }

    @Test
    @DisplayName("소스의 요청은 attempts 1로 남는다")
    void requestKeptWithOneAttempt() {
      // then
      assertThat(request).get().extracting(OutboxPort.Request::attempts).isEqualTo(1);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("스레드 문서만 실패한 투영을 backoff 뒤 재시도하면")
  class RetryAfterPartialFailure extends Situation {
    ProjectionRun.Report retried;
    int retryEmbedCalls;

    @BeforeAll
    void failOneThenRetry() {
      projection.collect();
      projection.embedderFailsOnInputContaining(THREAD_ONLY_TEXT);
      projection.project();
      projection.embedderRecovers();
      projection.advanceClock(FIRST_BACKOFF);
      int callsBefore = projection.embedCalls();
      retried = projection.project();
      retryEmbedCalls = projection.embedCalls() - callsBefore;
    }

    @Test
    @DisplayName("재시도는 성공한다")
    void retrySucceeds() {
      // then
      assertThat(retried.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("실패했던 스레드 문서만 임베딩한다(임베딩 호출 1번)")
    void embedsOnlyFailedDocument() {
      // then
      assertThat(retryEmbedCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 뒤 유닛은 중복 없이 TS 기대와 같다")
    void retryWritesUnitsOnce() {
      // then: 스레드 유닛이 나중에 쓰여 저장 순서는 기대와 다르다
      assertThat(projection.unitIds()).containsExactlyInAnyOrderElementsOf(expectedUnitIds());
    }

    @Test
    @DisplayName("재시도 뒤 요청 행은 완료된다")
    void requestCompletedAfterRetry() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("투영 중에 같은 소스의 요청이 새로 등록되면")
  class RequestDuringProjection extends Situation {
    static final String ARRIVED_TEXT = "새 메시지: 투영 중 도착한 결제 장애 공지";

    ProjectionRun.Report first;
    List<SourceInstanceId> pendingAfterFirst;
    ProjectionRun.Report second;

    @BeforeAll
    void projectTwice() {
      projection.collect();
      projection.requestArrivesDuringNextEmbedding();
      first = projection.project();
      pendingAfterFirst = projection.pending();
      projection.slackMessageAdded(ARRIVED_TEXT, "1760486430.000000");
      projection.collect();
      second = projection.project();
    }

    @Test
    @DisplayName("진행 중이던 투영은 성공한다")
    void firstSucceeds() {
      // then
      assertThat(first.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("완료가 그 요청을 지우지 않는다")
    void newRequestSurvivesCompletion() {
      // then
      assertThat(pendingAfterFirst).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("다음 투영이 새 항목을 반영한다")
    void nextProjectionAddsNewItem() {
      // given
      if (second.outcome() != ProjectionRun.Outcome.SUCCEEDED)
        throw new IllegalStateException("given 준비 실패: 둘째 투영이 성공해야 한다 — " + second);

      // then
      assertThat(projection.units()).anyMatch(u -> u.content().contains(ARRIVED_TEXT));
    }

    @Test
    @DisplayName("다음 투영 뒤 요청 행은 완료된다")
    void requestCompletedAfterNext() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("maxAttempts(2)만큼 실패하면")
  class AfterRepeatedFailures extends Situation {
    static final Duration PAST_BACKOFF = Duration.ofMinutes(10);
    static final Duration LONG_AFTER = Duration.ofDays(1);

    OutboxPort.Request afterFailures;
    List<ProjectionRun.Report> whileParked;
    OutboxPort.Request afterNewData;
    ProjectionRun.Report afterRecovery;

    @BeforeAll
    void failTwiceThenCollectNewData() {
      projection.collect();
      projection.embedderFails();
      projection.runDue();
      projection.advanceClock(PAST_BACKOFF);
      projection.runDue();
      afterFailures = projection.request().orElseThrow();
      projection.advanceClock(LONG_AFTER);
      whileParked = projection.runDue();
      projection.slackMessageAdded("새 메시지가 parked를 푼다", "1760486440.000000");
      projection.collect();
      afterNewData = projection.request().orElseThrow();
      projection.embedderRecovers();
      afterRecovery = projection.project();
    }

    @Test
    @DisplayName("요청은 두 번 실패해 parked된다")
    void requestIsParked() {
      // then
      assertThat(afterFailures)
          .extracting(OutboxPort.Request::attempts, OutboxPort.Request::parked)
          .containsExactly(2, true);
    }

    @Test
    @DisplayName("parked 요청은 시간이 지나도 due가 아니다")
    void parkedIsNotDue() {
      // then
      assertThat(whileParked).isEmpty();
    }

    @Test
    @DisplayName("새 데이터 수집이 parked를 푼다")
    void newDataUnparks() {
      // then
      assertThat(afterNewData.parked()).isFalse();
    }

    @Test
    @DisplayName("풀린 요청은 다음 투영에서 성공한다")
    void unparkedSucceeds() {
      // then
      assertThat(afterRecovery.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("같은 소스의 투영이 진행 중일 때 둘째 투영은")
  class WhileProjectionRunning extends Situation {
    ProjectionRun.Report second;
    List<SourceInstanceId> pendingDuringFirst;
    ProjectionRun.Report first;

    @BeforeAll
    void projectConcurrently() throws Exception {
      projection.collect();
      projection.holdNextEmbedding();
      Future<ProjectionRun.Report> inFlight = projection.projectInBackground();
      projection.awaitEmbeddingHeld();
      second = projection.projectAs("p2");
      pendingDuringFirst = projection.pending();
      projection.releaseEmbedding();
      first = ProjectionScenario.finish(inFlight);
    }

    @Test
    @DisplayName("SKIPPED_RUNNING이다")
    void isSkipped() {
      // then
      assertThat(second.outcome()).isEqualTo(ProjectionRun.Outcome.SKIPPED_RUNNING);
    }

    @Test
    @DisplayName("요청을 남긴다")
    void keepsRequest() {
      // then
      assertThat(pendingDuringFirst).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("진행 중이던 투영은 성공한다")
    void firstSucceeds() {
      // then
      assertThat(first.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("진행 중이던 투영이 요청을 완료한다")
    void firstCompletes() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("임베딩 중 점유를 잃은 투영이 늦게 쓰면")
  class StaleProjection extends Situation {
    static final String NEWER_TEXT = "배포 장애 회고(v2)";

    ProjectionRun.Report newer;
    ProjectionRun.Report stale;

    @BeforeAll
    void projectStaleAfterNewer() throws Exception {
      projection.collect();
      projection.holdNextEmbedding();
      Future<ProjectionRun.Report> old = projection.projectInBackground();
      projection.awaitEmbeddingHeld();
      projection.slackMessageEdited("1760400300.000000", NEWER_TEXT, "1760400500.000000");
      projection.collectSnapshot();
      projection.advanceClock(LEASE_TTL.plusSeconds(1));
      newer = projection.projectAs("p2");
      projection.releaseEmbedding();
      stale = ProjectionScenario.finish(old);
    }

    @Test
    @DisplayName("나중 투영은 새 내용을 쓴다")
    void newerSucceeds() {
      // then
      assertThat(newer.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("옛 투영은 실패가 아니라 SUPERSEDED다")
    void staleIsSuperseded() {
      // then
      assertThat(stale.outcome()).isEqualTo(ProjectionRun.Outcome.SUPERSEDED);
    }

    @Test
    @DisplayName("저장 경계가 옛 쓰기를 거부해 최신 내용이 유지된다")
    void newerContentKept() {
      // then
      assertThat(projection.units().get(0).content()).contains(NEWER_TEXT);
    }

    @Test
    @DisplayName("요청 행은 완료된다")
    void requestCompleted() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("투영이 TTL(30s)보다 오래 걸려도 heartbeat가 돌면")
  class LongProjectionWithHeartbeat extends Situation {
    /** 문서 3건 × 20s = 60s > TTL. */
    static final Duration PER_EMBEDDING = Duration.ofSeconds(20);

    ProjectionRun.Report report;

    @BeforeAll
    void projectSlowly() {
      projection.collect();
      projection.everyEmbeddingTakes(PER_EMBEDDING);
      report = projection.project();
    }

    @Test
    @DisplayName("SUCCEEDED로 끝난다")
    void succeeds() {
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("유닛이 TS 기대와 같다")
    void writesUnits() {
      // then
      assertThat(projection.unitIds()).containsExactlyElementsOf(expectedUnitIds());
    }

    @Test
    @DisplayName("요청 행은 완료된다")
    void requestCompleted() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("경쟁자 없이 점유가 만료되면")
  class ExpiredWithoutContender extends Situation {
    ProjectionRun.Report report;

    @BeforeAll
    void projectWithoutHeartbeat() {
      projection.collect();
      projection.claimExpiresDuringEachEmbedding();
      report = projection.project();
    }

    @Test
    @DisplayName("FAILED(claim_lost)로 끝난다")
    void failsWithClaimLost() {
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
      assertThat(report.error().orElseThrow()).startsWith("claim_lost");
    }

    @Test
    @DisplayName("실패로 세어 attempts가 오른다(무한 재시도 없음)")
    void countsAsAttempt() {
      // then
      OutboxPort.Request request = projection.request().orElseThrow();
      assertThat(request.attempts()).isEqualTo(1);
      assertThat(request.lastError().orElseThrow()).startsWith("claim_lost");
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("임베딩 세대 백필 중 점유를 잃으면")
  class StaleBackfill extends Situation {
    String currentGeneration;
    ProjectionRun.Report stale;

    @BeforeAll
    void backfillWhileCompetitorTakesOver() {
      projection.collect();
      projection.project();
      currentGeneration = projection.givenStaleEmbeddingGeneration();
      projection.requestProjection();
      projection.competitorTakesOverDuringNextEmbedding();
      stale = projection.project();
    }

    @Test
    @DisplayName("경쟁자의 백필·투영은 성공한다")
    void competitorSucceeds() {
      // then
      assertThat(projection.competitorReports())
          .singleElement()
          .extracting(ProjectionRun.Report::outcome)
          .isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("옛 백필은 SUPERSEDED다")
    void staleIsSuperseded() {
      // then
      assertThat(stale.outcome()).isEqualTo(ProjectionRun.Outcome.SUPERSEDED);
    }

    @Test
    @DisplayName("저장 경계가 거부해 경쟁자가 쓴 벡터를 덮지 않는다")
    void vectorsNotOverwritten() {
      // then
      Map<String, double[]> vectors = projection.vectors();
      assertThat(vectors.values())
          .as("유닛 3개의 벡터")
          .hasSize(3)
          .allSatisfy(v -> assertThat(v).doesNotContain(SENTINEL));
    }

    @Test
    @DisplayName("임베딩 세대는 경쟁자가 쓴 현재 값이다")
    void generationIsCurrent() {
      // then
      assertThat(projection.embeddingGeneration()).contains(currentGeneration);
    }

    @Test
    @DisplayName("요청 행은 완료된다")
    void requestCompleted() {
      // then
      assertThat(projection.pending()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("heartbeat 등록이 실패하면")
  class HeartbeatStartFails extends Situation {
    static final String REJECTION = "injected scheduler shutdown";

    ProjectionRun.Report report;
    boolean claimActiveAfter;
    Optional<OutboxPort.Request> request;
    ProjectionRun.Report afterBackoff;

    @BeforeAll
    void projectWithRejectedHeartbeats() {
      projection.collect();
      report = projection.projectWithHeartbeatsRejected(REJECTION);
      claimActiveAfter = projection.holdsActiveProjectionClaim();
      request = projection.request();
      projection.advanceClock(FIRST_BACKOFF);
      afterBackoff = projection.project();
    }

    @Test
    @DisplayName("주입한 원인으로 FAILED다")
    void failsWithCause() {
      // then
      assertThat(report.outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
      assertThat(report.error().orElseThrow()).contains(REJECTION);
    }

    @Test
    @DisplayName("투영 점유는 즉시 해제된다")
    void releasesClaim() {
      // then
      assertThat(claimActiveAfter).isFalse();
    }

    @Test
    @DisplayName("실패로 세어 attempts가 1이다")
    void countsAttempt() {
      // then
      assertThat(request.orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 투영은 TTL이 아니라 backoff만 기다리면 성공한다")
    void nextSucceedsAfterBackoff() {
      // then
      assertThat(afterBackoff.outcome()).isEqualTo(ProjectionRun.Outcome.SUCCEEDED);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("한 번 실패한 뒤 성공하면 투영 지표는")
  class Metrics extends Situation {
    static final Duration PAST_BACKOFF = Duration.ofMinutes(10);

    MeterRegistry metrics;

    @BeforeAll
    void failThenSucceed() {
      projection.collect();
      projection.embedderFails();
      projection.project();
      projection.embedderRecovers();
      projection.advanceClock(PAST_BACKOFF);
      projection.project();
      metrics = projection.metrics();
    }

    @Test
    @DisplayName("scos.projection.run은 실패 1과 성공 1을 잰다")
    void timesRunsByOutcome() {
      // then
      assertThatMetrics(metrics)
          .hasTimerCount(ProjectionMetrics.RUN, Tags.of("outcome", "failed"), 1)
          .hasTimerCount(ProjectionMetrics.RUN, Tags.of("outcome", "succeeded"), 1);
    }

    @Test
    @DisplayName("scos.projection.documents는 성공 문서 3을 센다")
    void countsSucceededDocuments() {
      // then
      assertThatMetrics(metrics)
          .hasCounter(ProjectionMetrics.DOCUMENTS, Tags.of("outcome", "ok", "reason", "none"), 3);
    }

    @Test
    @DisplayName("scos.projection.documents는 rate_limited 실패 문서 3을 센다")
    void countsFailedDocuments() {
      // then
      assertThatMetrics(metrics)
          .hasCounter(
              ProjectionMetrics.DOCUMENTS,
              Tags.of("outcome", "failed", "reason", "rate_limited"),
              3);
    }
  }
}
