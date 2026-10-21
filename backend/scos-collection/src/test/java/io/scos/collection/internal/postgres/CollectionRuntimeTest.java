package io.scos.collection.internal.postgres;

import static io.scos.collection.internal.postgres.CollectionScenario.HISTORY;
import static io.scos.collection.internal.postgres.CollectionScenario.MAIN_CHANNEL;
import static io.scos.collection.internal.postgres.CollectionScenario.SECOND_REPLIES_PAGE;
import static io.scos.collection.internal.postgres.CollectionScenario.TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.AlreadyRunningException;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.spi.connector.CancellationToken;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * 완료 기준(ADR-0018 §7): 중복 수집 없음, 두 worker 경합(하나만), 오래된 worker 쓰기 차단(fencing), 수정·삭제 반영, 부분 실패 스냅샷은
 * 삭제 0. 가짜 Slack 서버 + Testcontainers PostgreSQL. 상황마다 수집을 @BeforeAll에서 한 번 하고 기록을 계약별 테스트가
 * 읽는다(TESTING §2.5 규칙 6).
 */
@DisplayName("수집 런타임은 Slack·PostgreSQL 종단에서 완료 기준을 지킨다(ADR-0018 §7)")
@Tag("db")
@Timeout(180) // hang 방지 상한 — 동기화는 게이트·조건 대기로 한다
class CollectionRuntimeTest {
  static final String EDITED_TS = "1760400300.000000";
  static final String EDIT_TS = "1760400500.000000";
  static final String DELETED_TS = "1760400050.000000";

  /** 상황마다 시나리오 하나 — 실행·기록만 하고, 닫을 때 가짜 Slack의 미등록 요청을 확인한다. */
  abstract static class Situation {
    final CollectionScenario collection = new CollectionScenario();

    @AfterAll
    void close() {
      collection.close();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("빈 저장소에서 처음 수집하면")
  class FirstCollection extends Situation {
    CollectionRuntime.Report first;

    @BeforeAll
    void collectOnce() {
      first = collection.collect("w1");
    }

    @Test
    @DisplayName("ts 없는 항목 하나만 거부한 채 끝난다")
    void succeedsWithOneRejection() {
      // then
      assertThat(first)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::rejected)
          .containsExactly(RunStatus.SUCCEEDED_WITH_REJECTIONS, 1);
    }

    @Test
    @DisplayName("페이지 5개를 적용한다")
    void countsPages() {
      // then
      assertThat(first.pagesApplied()).isEqualTo(5);
    }

    @Test
    @DisplayName("upsert 9건·unchanged 1건으로 센다")
    void countsItems() {
      // then
      assertThat(first)
          .extracting(CollectionRuntime.Report::upserted, CollectionRuntime.Report::unchanged)
          .containsExactly(9, 1);
    }

    @Test
    @DisplayName("원본 9건이 저장된다")
    void storesItems() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("페이지마다 체크포인트를 남긴다(revision 5)")
    void checkpointsEachPage() {
      // then
      assertThat(collection.checkpointRevision()).isEqualTo(5);
    }

    @Test
    @DisplayName("투영 요청을 남긴다")
    void requestsProjection() {
      // then
      assertThat(collection.projectionRequested()).isTrue();
    }

    @Test
    @DisplayName("점유를 해제한다")
    void releasesClaim() {
      // then
      assertThat(collection.holdsActiveClaim()).isFalse();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("첫 수집 뒤 증분으로 다시 수집하면")
  class SecondCollection extends Situation {
    CollectionRuntime.Report second;

    @BeforeAll
    void collectTwice() {
      collection.collect("w1");
      second = collection.collect("w1");
    }

    @Test
    @DisplayName("워터마크 이후에는 새 항목이 없다")
    void findsNothingNew() {
      // then
      assertThat(second)
          .extracting(CollectionRuntime.Report::upserted, CollectionRuntime.Report::unchanged)
          .containsExactly(0, 0);
    }

    @Test
    @DisplayName("원본 행 수는 그대로다")
    void keepsItems() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("ts 없는 항목의 거부는 반복된다")
    void repeatsRejection() {
      // then
      assertThat(second)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::rejected)
          .containsExactly(RunStatus.SUCCEEDED_WITH_REJECTIONS, 1);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("겹침(historyOverlapSeconds 1000)으로 옛 메시지를 다시 받으면")
  class OverlapRefetch extends Situation {
    static final int OVERLAP_SECONDS = 1000;

    CollectionRuntime.Report refetch;
    Optional<RunId> latest;

    @BeforeAll
    void collectWithOverlap() {
      collection.collect("w1");
      collection.historyOverlapSeconds(OVERLAP_SECONDS);
      refetch = collection.collect("w1");
      latest = collection.latestRunId();
    }

    @Test
    @DisplayName("다시 받은 메시지는 전부 unchanged다")
    void allUnchanged() {
      // then
      assertThat(refetch.upserted()).isZero();
      assertThat(refetch.unchanged()).isGreaterThan(0);
    }

    @Test
    @DisplayName("원본 행 수는 그대로다")
    void keepsItems() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("그 run이 최신 run으로 기록된다")
    void recordedAsLatest() {
      // then
      assertThat(latest).contains(refetch.runId());
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("첫 worker가 수집 중일 때 둘째 worker가 수집하면")
  class SecondWorkerWhileRunning extends Situation {
    Throwable refusal;
    CollectionRuntime.Report first;
    Optional<RunId> latest;

    @BeforeAll
    void collectConcurrently() throws Exception {
      collection.holdRequest(HISTORY);
      Future<CollectionRuntime.Report> running = collection.collectInBackground("w1");
      collection.awaitRequestHeld();
      refusal = catchRefusal();
      collection.releaseRequest();
      first = CollectionScenario.finish(running);
      latest = collection.latestRunId();
    }

    Throwable catchRefusal() {
      try {
        collection.collect("w2");
        return null;
      } catch (RuntimeException e) {
        return e;
      }
    }

    @Test
    @DisplayName("둘째는 AlreadyRunning으로 거부된다")
    void secondIsRefused() {
      // then
      assertThat(refusal).isInstanceOf(AlreadyRunningException.class);
    }

    @Test
    @DisplayName("첫 worker는 끝까지 수집한다")
    void firstCollects() {
      // then
      assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
    }

    @Test
    @DisplayName("원본은 첫 worker가 넣은 9건이다")
    void onlyFirstCollects() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("최신 run은 첫 worker의 run이다")
    void latestRunIsFirst() {
      // then
      assertThat(latest).contains(first.runId());
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("A가 멈춘 사이 점유가 만료돼 B가 수집하면")
  class StaleWorker extends Situation {
    static final RunId RUN_A = new RunId("A-run-1");

    long revisionWhileHeld;
    CollectionRuntime.Report b;
    Throwable aFailure;
    CollectionRun runA;

    @BeforeAll
    void fenceA() throws Exception {
      collection.holdRequest(SECOND_REPLIES_PAGE);
      Future<CollectionRuntime.Report> a = collection.collectInBackground("A");
      collection.awaitRequestHeld();
      revisionWhileHeld = collection.checkpointRevision();
      collection.advanceClock(TTL.plusSeconds(1)); // A의 점유 만료(heartbeat renew도 만료 뒤라 실패한다)
      b = collection.collect("B");
      collection.releaseRequest();
      aFailure = CollectionScenario.failureOf(a);
      runA = collection.run(RUN_A);
    }

    @Test
    @DisplayName("A는 멈추기 전에 두 페이지를 커밋했다")
    void aCommittedTwoPages() {
      // then
      assertThat(revisionWhileHeld).isEqualTo(2);
    }

    @Test
    @DisplayName("B는 점유해 끝까지 수집한다")
    void bCollects() {
      // then
      assertThat(b.status()).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
    }

    @Test
    @DisplayName("A의 run 마감은 ClaimLost를 원인으로 실패한다")
    void aIsFenced() {
      // then
      assertThat(aFailure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Run finalization failed")
          .hasMessageContaining("not running")
          .hasCauseInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("B의 run 시작이 A의 run을 failed(stale_run)로 마감한다")
    void aRunIsStale() {
      // then
      assertThat(runA)
          .extracting(CollectionRun::status, CollectionRun::errorKind)
          .containsExactly(RunStatus.FAILED, Optional.of("stale_run"));
    }

    @Test
    @DisplayName("원본은 B 기준으로만 9건이다")
    void dataIsB() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("체크포인트는 A의 2페이지 + B가 이어 받은 3페이지다")
    void bResumesFromCheckpoint() {
      // then
      assertThat(collection.checkpointRevision()).isEqualTo(5);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("편집된 메시지를 스냅샷으로 다시 수집하면")
  class EditedMessage extends Situation {
    CollectionRuntime.Report edited;

    @BeforeAll
    void editAndCollect() {
      collection.collect("w1");
      collection.slackMessageEdited(EDITED_TS, "배포 장애 회고(수정됨)", EDIT_TS);
      edited = collection.collectSnapshot("w1");
      if (edited.status() != RunStatus.SUCCEEDED_WITH_REJECTIONS)
        throw new IllegalStateException("given 준비 실패: 편집 뒤 스냅샷 수집이 끝나지 않았다 — " + edited.status());
    }

    @Test
    @DisplayName("그 항목 하나만 upsert로 센다")
    void upsertsOnlyEdited() {
      // then
      assertThat(edited.upserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("원본 행 수는 그대로다")
    void keepsItemCount() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
    }

    @Test
    @DisplayName("새 sourceVersion은 edited.ts다")
    void newSourceVersion() {
      // then
      assertThat(collection.rawItem(MAIN_CHANNEL + ":" + EDITED_TS))
          .get()
          .isInstanceOfSatisfying(
              RawRecord.Active.class,
              active -> assertThat(active.item().sourceVersion()).isEqualTo(EDIT_TS));
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("메시지 삭제 뒤 스냅샷이 replies 연속 실패로 부분 성공하면(불변식 6)")
  class PartialSnapshot extends Situation {
    static final int REPLIES_FAILURES = 5;

    CollectionRuntime.Report partial;

    @BeforeAll
    void failMidSnapshot() {
      collection.givenNoRejectedItems(); // 성공했다면 reconcile 대상이 됐을 run
      collection.collect("w1");
      collection.slackMessageDeleted(DELETED_TS);
      collection.repliesFailNext(REPLIES_FAILURES);
      partial = collection.collectSnapshot("w1");
    }

    @Test
    @DisplayName("dependency_unavailable로 부분 성공한다")
    void partiallySucceeds() {
      // then
      assertThat(partial)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::errorKind)
          .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, Optional.of("dependency_unavailable"));
    }

    @Test
    @DisplayName("사라진 메시지를 지우지 않는다")
    void deletesNothing() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
      assertThat(collection.tombstones()).isZero();
    }

    @Test
    @DisplayName("보고의 삭제 수는 0이다")
    void reportsNoDeletion() {
      // then
      assertThat(partial.deleted()).isZero();
    }

    @Test
    @DisplayName("스냅샷 완료를 기록하지 않는다")
    void recordsNoSnapshotCompletion() {
      // then
      assertThat(collection.lastSnapshotCompletion()).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("메시지 삭제 뒤 스냅샷 run이 거부 없이 성공하면")
  class SucceededSnapshot extends Situation {
    CollectionRuntime.Report full;

    @BeforeAll
    void snapshotAfterDelete() {
      collection.givenNoRejectedItems();
      collection.collect("w1");
      collection.slackMessageDeleted(DELETED_TS);
      full = collection.collectSnapshot("w1");
    }

    @Test
    @DisplayName("사라진 메시지 하나를 삭제로 센다")
    void countsDeletion() {
      // then
      assertThat(full)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::deleted)
          .containsExactly(RunStatus.SUCCEEDED, 1);
    }

    @Test
    @DisplayName("원본이 하나 줄어 8건이다")
    void removesDeletedItem() {
      // then
      assertThat(collection.rawItems()).isEqualTo(8);
    }

    @Test
    @DisplayName("사라진 메시지는 tombstone이 된다")
    void tombstonesAbsentMessage() {
      // then
      assertThat(collection.rawItem(MAIN_CHANNEL + ":" + DELETED_TS))
          .get()
          .isInstanceOf(RawRecord.Deleted.class);
    }

    @Test
    @DisplayName("삭제 수와 함께 스냅샷 완료를 기록한다")
    void recordsSnapshotCompletion() {
      // then
      assertThat(collection.lastSnapshotCompletion())
          .map(RunStore.SnapshotCompletion::deletionCount)
          .contains(1);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("게이트에서 멈춘 수집을 취소하면")
  class Cancelled extends Situation {
    CollectionRuntime.Report report;
    CollectionRun recorded;

    @BeforeAll
    void cancelHeldCollection() throws Exception {
      collection.holdRequest(SECOND_REPLIES_PAGE);
      CancellationToken token = new CancellationToken();
      Future<CollectionRuntime.Report> run = collection.collectInBackground("A", token);
      collection.awaitRequestHeld();
      token.cancel();
      collection.releaseRequest();
      report = CollectionScenario.finish(run);
      recorded = collection.run(report.runId());
    }

    @Test
    @DisplayName("보고는 오류 없이 cancelled다")
    void recordedAsCancelled() {
      // then
      assertThat(report)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::errorCount)
          .containsExactly(RunStatus.CANCELLED, 0);
    }

    @Test
    @DisplayName("저장된 run도 cancelled다")
    void storedRunIsCancelled() {
      // then
      assertThat(recorded.status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 커밋된 페이지는 남는다")
    void keepsCommittedPages() {
      // then
      assertThat(collection.checkpointRevision()).isGreaterThanOrEqualTo(1);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("TTL 안에 heartbeat tick이 돈 뒤 원래 TTL을 지나 이어 수집하면")
  class HeartbeatWithinTtl extends Situation {
    /** TTL 안에서 tick. */
    static final Duration BEFORE_EXPIRY = TTL.minusSeconds(5);

    static final Duration PAST_ORIGINAL_TTL = Duration.ofSeconds(10);

    Instant extendedTo;
    Instant expectedExpiry;
    Instant claimedAt;
    Instant resumedAt;
    CollectionRuntime.Report report;

    @BeforeAll
    void tickDuringRun() throws Exception {
      collection.holdRequest(SECOND_REPLIES_PAGE);
      Future<CollectionRuntime.Report> run = collection.collectInBackground("w1");
      collection.awaitRequestHeld();
      claimedAt = collection.now();
      collection.advanceClock(BEFORE_EXPIRY);
      collection.tickHeartbeats();
      extendedTo = collection.claimExpiresAt();
      expectedExpiry = collection.now().plus(TTL);
      collection.advanceClock(PAST_ORIGINAL_TTL);
      resumedAt = collection.now();
      collection.releaseRequest();
      report = CollectionScenario.finish(run);
      if (!resumedAt.isAfter(claimedAt.plus(TTL)))
        throw new IllegalStateException("given 준비 실패: 이어 수집한 시각이 원래 TTL을 지나야 한다");
    }

    @Test
    @DisplayName("점유는 tick 시각 + TTL로 연장된다")
    void extendsClaim() {
      // then
      assertThat(extendedTo).isEqualTo(expectedExpiry);
    }

    @Test
    @DisplayName("원래 TTL이 지난 뒤에도 뒤 페이지가 커밋된다")
    void laterPagesCommit() {
      // then
      assertThat(report)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::pagesApplied)
          .containsExactly(RunStatus.SUCCEEDED_WITH_REJECTIONS, 5);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("게이트에서 멈춘 사이 점유가 만료돼 heartbeat renew가 실패하면")
  class HeartbeatFails extends Situation {
    CollectionRuntime.Report report;

    @BeforeAll
    void tickAfterExpiry() throws Exception {
      collection.holdRequest(SECOND_REPLIES_PAGE);
      Future<CollectionRuntime.Report> run = collection.collectInBackground("w1");
      collection.awaitRequestHeld();
      collection.advanceClock(TTL.plusSeconds(1));
      collection.tickHeartbeats(); // 만료 뒤 renew → 실패 플래그
      collection.releaseRequest();
      report = CollectionScenario.finish(run);
    }

    @Test
    @DisplayName("다음 페이지 전에 드러나 claim_lost로 부분 성공한다")
    void endsWithClaimLost() {
      // then
      assertThat(report)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::errorKind)
          .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, Optional.of("claim_lost"));
    }

    @Test
    @DisplayName("게이트 뒤 페이지는 커밋되지 않는다")
    void laterPagesNotCommitted() {
      // then
      assertThat(collection.checkpointRevision()).isEqualTo(2);
    }

    @Test
    @DisplayName("보고는 게이트 앞 두 페이지만 센다")
    void reportsPagesBeforeGate() {
      // then
      assertThat(report.pagesApplied()).isEqualTo(2);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("거부 없는 증분 수집을 두 번 succeeded로 끝내면(불변식 6)")
  class IncrementalNeverReconciles extends Situation {
    CollectionRuntime.Report first;
    CollectionRuntime.Report second;

    @BeforeAll
    void collectTwiceWithoutRejections() {
      collection.givenNoRejectedItems();
      first = collection.collect("w1");
      second = collection.collect("w1");
    }

    @Test
    @DisplayName("두 run 모두 succeeded다")
    void bothSucceed() {
      // then
      assertThat(List.of(first.status(), second.status()))
          .containsExactly(RunStatus.SUCCEEDED, RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("둘째 run은 새로 넣는 것이 없다")
    void secondInsertsNothing() {
      // then
      assertThat(second.upserted()).isZero();
    }

    @Test
    @DisplayName("coverage incremental이라 미관측 항목을 지우지 않는다")
    void deletesNothing() {
      // then
      assertThat(collection.rawItems()).isEqualTo(9);
      assertThat(collection.tombstones()).isZero();
    }

    @Test
    @DisplayName("둘째 run 보고의 삭제 수는 0이다")
    void reportsNoDeletion() {
      // then
      assertThat(second.deleted()).isZero();
    }

    @Test
    @DisplayName("스냅샷 완료를 기록하지 않는다")
    void recordsNoSnapshotCompletion() {
      // then
      assertThat(collection.lastSnapshotCompletion()).isEmpty();
    }
  }
}
