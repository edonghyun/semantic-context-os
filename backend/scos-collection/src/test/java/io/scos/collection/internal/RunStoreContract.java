package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** run 저장 포트 계약: stale run 마감, running 검사, 카운터·관측·rejection·완료·스냅샷 완료·최근 실패 수. */
@DisplayName("run 저장소는 run 기록·관측·스냅샷 완료를 소스별로 둔다")
public abstract class RunStoreContract {
  protected static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  protected static final SourceInstanceId OTHER_SOURCE = new SourceInstanceId("src-b");
  protected static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final RunId DEAD = new RunId("r-dead");
  static final RunId NEW = new RunId("r-new");
  static final RunId OTHER = new RunId("r-other-source");
  static final RunId R1 = new RunId("r1");
  static final Instant RESTART = T0.plusSeconds(5);
  static final Instant FINISHED = T0.plusSeconds(9);

  /** 64KiB 인라인 상한을 넘는 격리 콘텐츠. */
  static final String BIG_JSON = "{\"text\":\"" + "q".repeat(70_000) + "\"}";

  protected RunStore store;

  protected abstract RunStore newStore();

  protected abstract AccessObservationStore accessObservations();

  protected abstract long accessObservationCount(RunId run);

  /** 격리 blob 데이터 읽기(공용 blob 저장소). */
  protected abstract Optional<byte[]> storedBlob(String sha256);

  @BeforeEach
  void setUpContract() {
    store = newStore();
  }

  @Test
  @DisplayName("빈 독자 거부 관측은 없는 run에도 무동작이다")
  void emptyUnreadableObservationIsNoOp() {
    // when·then
    assertThatCode(() -> store.observeUnreadableAcls(new RunId("missing"), Set.of()))
        .doesNotThrowAnyException();
    assertThat(store.get(new RunId("missing"))).isEmpty();
  }

  @Test
  @DisplayName("독자 거부 파일은 중복 없이 run 결과와 마감 뒤에도 보존한다")
  void unreadableAclIdsSurviveCompletion() {
    store.start(R1, SOURCE, T0);
    store.observeUnreadableAcls(R1, Set.of("file:F1"));
    store.observeUnreadableAcls(R1, Set.of("file:F1", "file:F2"));
    var finished =
        store.finish(
            R1,
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            FINISHED,
            0,
            AccessResult.COMPLETE,
            Optional.of(FINISHED));
    // then
    assertThat(finished.aclUnreadable()).containsExactlyInAnyOrder("file:F1", "file:F2");
    assertThat(store.get(R1).orElseThrow().aclUnreadable())
        .containsExactlyInAnyOrder("file:F1", "file:F2");
  }

  @Test
  @DisplayName("새 run은 같은 소스의 stale 접근 관측만 지운다")
  void staleRunDiscardsOnlyItsAccessObservations() {
    // given
    store.start(R1, SOURCE, T0);
    stageAccess(R1);
    finishAs(R1, RunStatus.SUCCEEDED, T0.plusSeconds(1));
    store.start(DEAD, SOURCE, T0);
    stageAccess(DEAD);
    store.start(OTHER, OTHER_SOURCE, T0);
    stageAccess(OTHER);
    // when
    store.start(NEW, SOURCE, RESTART);
    // then
    assertThat(
            Map.of(
                DEAD,
                accessObservationCount(DEAD),
                OTHER,
                accessObservationCount(OTHER),
                R1,
                accessObservationCount(R1)))
        .containsExactlyInAnyOrderEntriesOf(Map.of(DEAD, 0L, OTHER, 2L, R1, 2L));
  }

  private void stageAccess(RunId run) {
    accessObservations()
        .stage(
            run,
            new AclPage(
                Map.of(),
                List.of(new GroupSnapshot("C1", Set.of("U1"), T0)),
                List.of(
                    new SourceIdentityRecord(
                        "U1", Optional.of("user@example.com"), true, false, false, T0))));
  }

  CollectionRun finishAs(RunId id, RunStatus status, Instant at) {
    return store.finish(
        id,
        new RunOutcome(
            status, status == RunStatus.SUCCEEDED ? 0 : 1, Optional.empty(), Optional.empty()),
        CollectionCoverage.INCREMENTAL,
        at,
        0,
        AccessResult.SKIPPED,
        Optional.empty());
  }

  @Test
  @DisplayName("전체 정리 완료 기준은 같은 소스의 같은 범위 세대에만 유효하다")
  void reconciliationBaselineIsBoundToGeneration() {
    // given
    store.recordSnapshotCompletion(SOURCE, T0, 0, Optional.of("roots-generation"));
    // when
    var baselines =
        List.of(
            store.hasReconciliationBaseline(SOURCE, "roots-generation"),
            store.hasReconciliationBaseline(SOURCE, "other-generation"),
            store.hasReconciliationBaseline(OTHER_SOURCE, "roots-generation"));
    // then
    assertThat(baselines).containsExactly(true, false, false);
  }

  // ---- 새 run 시작과 stale run ----

  @Nested
  @DisplayName("관측을 남긴 running run(다른 소스에도 하나)이 있을 때 새 run을 시작하면")
  class RestartOverRunningRun {
    @BeforeEach
    void givenRunningRuns() {
      store.start(DEAD, SOURCE, T0);
      store.observe(DEAD, List.of("a"));
      store.start(OTHER, OTHER_SOURCE, T0);
    }

    CollectionRun startNew() {
      return store.start(NEW, SOURCE, RESTART);
    }

    @Test
    @DisplayName("새 run은 running으로 시작한다")
    void newRunIsRunning() {
      // when
      CollectionRun fresh = startNew();

      // then
      assertThat(fresh.status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("같은 소스의 running run을 그 시각에 stale_run으로 마감한다")
    void startFailsStaleRunningRun() {
      // when: ADR-0008 E-5
      startNew();

      // then
      assertThat(store.get(DEAD).orElseThrow())
          .extracting(CollectionRun::status, CollectionRun::errorKind, CollectionRun::finishedAt)
          .containsExactly(RunStatus.FAILED, Optional.of("stale_run"), Optional.of(RESTART));
    }

    @Test
    @DisplayName("stale run의 종료 실패는 오류 수에 한 건을 더한다")
    void supersededRunCountsOneFailure() {
      startNew();
      // then
      assertThat(store.get(DEAD).orElseThrow().errorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("마감한 stale run의 관측을 지운다")
    void startClearsStaleObservations() {
      // when
      startNew();

      // then
      assertThat(store.observations(DEAD)).isEmpty();
    }

    @Test
    @DisplayName("다른 소스의 running run은 건드리지 않는다")
    void startLeavesOtherSources() {
      // when
      startNew();

      // then
      assertThat(store.get(OTHER).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("latest는 새 run이다")
    void latestIsNewRun() {
      // when
      startNew();

      // then
      assertThat(store.latest(SOURCE).orElseThrow().id()).isEqualTo(NEW);
    }
  }

  @Test
  @DisplayName("이미 끝난 run은 새 run 시작이 덮어쓰지 않는다(running만 마감한다)")
  void startDoesNotOverwriteFinishedRuns() {
    // given
    store.start(R1, SOURCE, T0);
    store.finish(
        R1,
        RunOutcome.failed(RunStatus.PARTIALLY_SUCCEEDED, "rate_limited", "429"),
        CollectionCoverage.INCREMENTAL,
        T0.plusSeconds(1),
        0,
        AccessResult.SKIPPED,
        Optional.empty());

    // when
    store.start(new RunId("r2"), SOURCE, RESTART);

    // then
    assertThat(store.get(R1).orElseThrow())
        .extracting(CollectionRun::status, CollectionRun::errorKind)
        .containsExactly(RunStatus.PARTIALLY_SUCCEEDED, Optional.of("rate_limited"));
  }

  // ---- requireRunning ----

  @Test
  @DisplayName("requireRunning은 없는 run을 거부한다")
  void requireRunningRejectsUnknownRun() {
    // when·then
    assertThatThrownBy(() -> store.requireRunning(new RunId("nope"), SOURCE))
        .isInstanceOf(RunStateException.class);
  }

  @Nested
  @DisplayName("r1이 src-a에서 시작된 상태에서")
  class R1Started {
    @BeforeEach
    void givenStarted() {
      store.start(R1, SOURCE, T0);
    }

    @Test
    @DisplayName("본문 성공과 독립된 접근 미완료 결과를 저장한다")
    void finishPersistsIndependentAccessResult() {
      // when
      store.finish(
          R1,
          RunOutcome.succeeded(0),
          CollectionCoverage.INCREMENTAL,
          FINISHED,
          0,
          AccessResult.INCOMPLETE,
          Optional.of(RESTART));
      // then
      assertThat(store.get(R1).orElseThrow())
          .extracting(
              CollectionRun::status, CollectionRun::accessResult, CollectionRun::accessFinishedAt)
          .containsExactly(RunStatus.SUCCEEDED, AccessResult.INCOMPLETE, Optional.of(RESTART));
    }

    @Test
    @DisplayName("건너뛴 접근 단계에는 완료 시각이 없다")
    void skippedAccessHasNoFinishTime() {
      // when
      var finished =
          store.finish(
              R1,
              RunOutcome.succeeded(0),
              CollectionCoverage.INCREMENTAL,
              FINISHED,
              0,
              AccessResult.SKIPPED,
              Optional.empty());
      // then
      assertThat(finished)
          .extracting(CollectionRun::accessResult, CollectionRun::accessFinishedAt)
          .containsExactly(AccessResult.SKIPPED, Optional.empty());
    }

    @Test
    @DisplayName("접근 실패는 본문 성공 최신성에 영향을 주지 않는다")
    void accessFailureDoesNotChangeSuccessFreshness() {
      // given
      store.finish(
          R1,
          RunOutcome.succeeded(0),
          CollectionCoverage.INCREMENTAL,
          FINISHED,
          0,
          AccessResult.INCOMPLETE,
          Optional.of(RESTART));
      // when
      var succeeded = store.lastSucceededAt(Set.of(SOURCE));
      // then
      assertThat(succeeded).containsExactlyEntriesOf(Map.of(SOURCE, FINISHED));
    }

    @ParameterizedTest
    @EnumSource(AccessResult.class)
    @DisplayName("최신 run 조회는 접근 결과와 시각을 보존한다")
    void latestPreservesAccessResult(AccessResult result) {
      // given
      var at = result == AccessResult.SKIPPED ? Optional.<Instant>empty() : Optional.of(RESTART);
      store.finish(
          R1, RunOutcome.succeeded(0), CollectionCoverage.INCREMENTAL, FINISHED, 0, result, at);
      // when
      var latest = store.latest(SOURCE).orElseThrow();
      // then
      assertThat(latest)
          .extracting(CollectionRun::accessResult, CollectionRun::accessFinishedAt)
          .containsExactly(result, at);
    }

    @Test
    @DisplayName("requireRunning은 다른 소스의 run을 거부한다")
    void requireRunningRejectsOtherSource() {
      // when·then
      assertThatThrownBy(() -> store.requireRunning(R1, OTHER_SOURCE))
          .isInstanceOf(RunStateException.class);
    }

    @Test
    @DisplayName("requireRunning은 끝난 run을 거부한다")
    void requireRunningRejectsFinishedRun() {
      // given
      finishAs(R1, RunStatus.CANCELLED, T0);

      // when·then
      assertThatThrownBy(() -> store.requireRunning(R1, SOURCE))
          .isInstanceOf(RunStateException.class);
    }

    @Test
    @DisplayName("실패 완료는 오류 종류와 요약을 쓴다")
    void failedFinishWritesError() {
      // when
      CollectionRun failed =
          store.finish(
              R1,
              RunOutcome.failed(RunStatus.FAILED, "rate_limited", "429"),
              CollectionCoverage.INCREMENTAL,
              FINISHED,
              0,
              AccessResult.SKIPPED,
              Optional.empty());

      // then
      assertThat(failed)
          .extracting(CollectionRun::errorKind, CollectionRun::errorSummary)
          .containsExactly(Optional.of("rate_limited"), Optional.of("429"));
    }
  }

  // ---- 진행 중 기록과 완료 ----

  @Nested
  @DisplayName("페이지 둘·겹치는 관측 둘·rejection 하나를 쌓은 run에서")
  class RunWithTwoPages {
    RejectedItem rejected;

    /** 페이지 둘(upsert 2·1, delete 0·1, unchanged 1·0, rejected 1·0), 관측 {a,b}·{b,c}, rejection 하나. */
    @BeforeEach
    void givenRunWithTwoPages() {
      store.start(R1, SOURCE, T0);
      store.addPage(R1, 2, 0, 1, 1);
      store.addPage(R1, 1, 1, 0, 0);
      store.observe(R1, List.of("a", "b"));
      store.observe(R1, List.of("b", "c"));
      rejected =
          new RejectedItem(
              RejectedItem.FailureKind.INVALID_ITEM,
              "no ts",
              Optional.of("{\"channelId\":\"C1\",\"messageIndex\":1}"),
              T0,
              Optional.of(new RawContent.Inline("application/json", "{\"type\":\"bot\"}")));
      store.addRejections(R1, SOURCE, T0, List.of(rejected));
    }

    /** 4건을 reconcile하며 성공으로 끝낸다. */
    CollectionRun finishReconciling() {
      return store.finish(
          R1,
          RunOutcome.succeeded(0),
          CollectionCoverage.COMPLETE_SNAPSHOT,
          FINISHED,
          4,
          AccessResult.SKIPPED,
          Optional.empty());
    }

    @Test
    @DisplayName("페이지 카운터가 누적된다")
    void pageCountersAccumulate() {
      // when
      CollectionRun running = store.requireRunning(R1, SOURCE);

      // then
      assertThat(running)
          .extracting(
              CollectionRun::pagesApplied,
              CollectionRun::upserted,
              CollectionRun::deleted,
              CollectionRun::unchanged,
              CollectionRun::rejected)
          .containsExactly(2, 3, 1, 1, 1);
    }

    @Test
    @DisplayName("관측은 중복 없이 합쳐진다")
    void observationsAreUnioned() {
      // when
      var observed = store.observations(R1);

      // then
      assertThat(observed).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("rejection을 그대로 저장한다")
    void storesRejections() {
      // when
      List<RejectedItem> stored = store.rejections(R1);

      // then
      assertThat(stored).containsExactly(rejected);
    }

    @Test
    @DisplayName("완료는 상태·coverage·완료 시각을 쓴다")
    void finishWritesOutcome() {
      // when
      CollectionRun done = finishReconciling();

      // then
      assertThat(done)
          .extracting(CollectionRun::status, CollectionRun::coverage, CollectionRun::finishedAt)
          .containsExactly(
              RunStatus.SUCCEEDED,
              Optional.of(CollectionCoverage.COMPLETE_SNAPSHOT),
              Optional.of(FINISHED));
    }

    @Test
    @DisplayName("완료는 reconcile 수를 삭제 수에 더한다")
    void finishAddsReconciledDeletes() {
      // when
      CollectionRun done = finishReconciling();

      // then
      assertThat(done.deleted()).isEqualTo(5);
    }

    @Test
    @DisplayName("완료는 관측을 지운다")
    void finishClearsObservations() {
      // when
      finishReconciling();

      // then
      assertThat(store.observations(R1)).isEmpty();
    }
  }

  @Nested
  @DisplayName("64KiB를 넘는 콘텐츠의 거부 항목을 저장한 상태에서")
  class BigRejectionStored {
    RawContent.Blob stored;

    @BeforeEach
    void givenBigRejectionStored() {
      store.start(R1, SOURCE, T0);
      RawContent captured = RawContentCapture.defaults().json("application/json", BIG_JSON);
      if (!(captured instanceof RawContent.Blob))
        throw new IllegalStateException("given 준비 실패: 큰 콘텐츠가 blob으로 잡히지 않았다");
      store.addRejections(R1, SOURCE, T0, List.of(rejectionOf(captured)));
      stored = (RawContent.Blob) store.rejections(R1).get(0).quarantined().orElseThrow();
    }

    @Test
    @DisplayName("격리 콘텐츠는 데이터 없이 blob 참조로 저장된다")
    void quarantinedBlobIsStored() {
      // then
      assertThat(stored.data()).isEmpty();
    }

    @Test
    @DisplayName("격리 blob 데이터는 sha256으로 읽힌다")
    void quarantinedBlobRoundTrip() {
      // when
      Optional<byte[]> data = storedBlob(stored.sha256());

      // then
      assertThat(new String(data.orElseThrow(), StandardCharsets.UTF_8)).isEqualTo(BIG_JSON);
    }
  }

  static RejectedItem rejectionOf(RawContent quarantined) {
    return new RejectedItem(
        RejectedItem.FailureKind.INVALID_ITEM,
        "no ts",
        Optional.empty(),
        T0,
        Optional.of(quarantined));
  }

  // ---- 스냅샷 완료 ----

  @Test
  @DisplayName("스냅샷 완료 기록이 없으면 비어 있다")
  void noSnapshotCompletion() {
    // when
    Optional<RunStore.SnapshotCompletion> last = store.lastSnapshotCompletion(SOURCE);

    // then
    assertThat(last).isEmpty();
  }

  @Test
  @DisplayName("스냅샷 완료 기록은 소스당 최신 하나다")
  void latestSnapshotCompletionWins() {
    // when
    store.recordSnapshotCompletion(SOURCE, T0, 2);
    store.recordSnapshotCompletion(SOURCE, T0.plusSeconds(1), 0);

    // then
    assertThat(store.lastSnapshotCompletion(SOURCE))
        .contains(new RunStore.SnapshotCompletion(T0.plusSeconds(1), 0));
  }

  @Test
  @DisplayName("원천별 마지막 성공 완료 시각을 돌려준다")
  void lastSucceededUsesLatestFinish() {
    // given: 삽입·시작 순서가 아닌 완료 시각으로 고른다
    store.start(R1, SOURCE, T0);
    finishAs(R1, RunStatus.SUCCEEDED, FINISHED);
    store.start(NEW, SOURCE, RESTART);
    finishAs(NEW, RunStatus.SUCCEEDED, RESTART);

    // when
    var succeeded = store.lastSucceededAt(Set.of(SOURCE));

    // then
    assertThat(succeeded).isEqualTo(Map.of(SOURCE, FINISHED));
  }

  @Test
  @DisplayName("거부 항목이 있는 성공 run도 완료 시각을 준다")
  void succeededWithRejectionsHasFinish() {
    // given
    givenFinishedRuns(RunStatus.SUCCEEDED_WITH_REJECTIONS);

    // when
    var succeeded = store.lastSucceededAt(Set.of(SOURCE));

    // then
    assertThat(succeeded).containsOnlyKeys(SOURCE);
  }

  @ParameterizedTest(name = "{0}는 성공이 아니다")
  @EnumSource(
      value = RunStatus.class,
      names = {"FAILED", "CANCELLED", "RUNNING", "PARTIALLY_SUCCEEDED"})
  @DisplayName("비성공 run은 성공 완료 시각을 주지 않는다")
  void nonSuccessHasNoFinish(RunStatus status) {
    // given
    store.start(R1, SOURCE, T0);
    if (status != RunStatus.RUNNING) finishAs(R1, status, FINISHED);

    // when
    var succeeded = store.lastSucceededAt(Set.of(SOURCE));

    // then
    assertThat(succeeded).isEmpty();
  }

  @ParameterizedTest(name = "{0}의 성공 판정")
  @EnumSource(RunStatus.class)
  @DisplayName("마지막 성공 조회의 상태 판정은 RunStatus.isSuccess와 같다")
  void successMatchesRunStatus(RunStatus status) {
    // given
    store.start(R1, SOURCE, T0);
    if (status != RunStatus.RUNNING) finishAs(R1, status, FINISHED);

    // when
    var succeeded = store.lastSucceededAt(Set.of(SOURCE));

    // then
    assertThat(succeeded.containsKey(SOURCE)).isEqualTo(status.isSuccess());
  }

  @Test
  @DisplayName("요청하지 않은 인스턴스의 성공 시각은 제외한다")
  void lastSucceededOmitsUnrequested() {
    // given
    givenFinishedRuns(RunStatus.SUCCEEDED);

    // when
    var succeeded = store.lastSucceededAt(Set.of(OTHER_SOURCE));

    // then
    assertThat(succeeded).isEmpty();
  }

  @Test
  @DisplayName("요청 인스턴스가 비면 성공 시각도 비어 있다")
  void lastSucceededEmptyRequest() {
    // given
    givenFinishedRuns(RunStatus.SUCCEEDED);

    // when
    var succeeded = store.lastSucceededAt(Set.of());

    // then
    assertThat(succeeded).isEmpty();
  }

  // ---- 최근 실패 수 ----

  /** 같은 소스에 차례로 끝난 run들(10초 간격). */
  void givenFinishedRuns(RunStatus... statuses) {
    for (int i = 0; i < statuses.length; i++) {
      RunId id = new RunId("seq-" + i);
      store.start(id, SOURCE, T0.plusSeconds(10L * i));
      finishAs(id, statuses[i], T0.plusSeconds(10L * i + 1));
    }
  }

  @Test
  @DisplayName("run이 없으면 최근 실패 수는 0이다")
  void noRunsMeansNoFailures() {
    // when
    int failures = store.recentFailureCount(SOURCE);

    // then
    assertThat(failures).isZero();
  }

  @Test
  @DisplayName("성공 직후 최근 실패 수는 0이다")
  void successResetsFailures() {
    // given
    givenFinishedRuns(RunStatus.FAILED, RunStatus.SUCCEEDED);

    // when
    int failures = store.recentFailureCount(SOURCE);

    // then
    assertThat(failures).isZero();
  }

  @Test
  @DisplayName("최근 실패 수는 마지막 성공 뒤의 실패 run 수다")
  void countsFailuresSinceLastSuccess() {
    // given: failed·partially_succeeded만 센다(running·다른 소스 무관)
    givenFinishedRuns(
        RunStatus.FAILED,
        RunStatus.SUCCEEDED,
        RunStatus.FAILED,
        RunStatus.PARTIALLY_SUCCEEDED,
        RunStatus.CANCELLED);
    store.start(OTHER, OTHER_SOURCE, T0.plusSeconds(42));
    finishAs(OTHER, RunStatus.FAILED, T0.plusSeconds(43));
    store.start(NEW, SOURCE, T0.plusSeconds(50)); // running

    // when
    int failures = store.recentFailureCount(SOURCE);

    // then
    assertThat(failures).isEqualTo(2);
  }

  @Test
  @DisplayName("종료(SIGTERM)로 취소된 run은 실패로 세지 않는다(TS와 같다)")
  void cancelledRunsDoNotCount() {
    // given
    givenFinishedRuns(RunStatus.FAILED, RunStatus.CANCELLED);

    // when
    int failures = store.recentFailureCount(SOURCE);

    // then
    assertThat(failures).isEqualTo(1);
  }

  @Test
  @DisplayName("succeeded_with_rejections도 성공으로 보아 최근 실패 수를 끊는다")
  void rejectionsCountAsSuccess() {
    // given
    givenFinishedRuns(RunStatus.FAILED, RunStatus.SUCCEEDED_WITH_REJECTIONS);

    // when
    int failures = store.recentFailureCount(SOURCE);

    // then
    assertThat(failures).isZero();
  }

  @Nested
  @DisplayName("현재 run의 전체 부재 검증을 요구하면")
  class AbsenceVerification {
    static final String FINGERPRINT = "roots-generation";

    @BeforeEach
    void givenRunning() {
      store.start(R1, SOURCE, T0);
    }

    @Test
    @DisplayName("해당 run에만 검증할 범위 세대를 저장한다")
    void storesAbsenceRequirement() {
      // when
      store.requireAbsenceVerification(R1, FINGERPRINT);
      // then
      assertThat(store.absenceRequirement(R1)).contains(FINGERPRINT);
    }

    @Test
    @DisplayName("최종 후보 집합과 제외 증거를 현재 run에서 다시 읽는다")
    void storesAbsenceValidation() {
      // given
      store.requireAbsenceVerification(R1, FINGERPRINT);
      var validation =
          new RunStore.AbsenceValidation(FINGERPRINT, "candidate-token", Set.of("outside"));
      // when
      store.recordAbsenceValidation(R1, validation);
      // then
      assertThat(store.absenceValidation(R1)).contains(validation);
    }

    @Test
    @DisplayName("진행 중 검증의 범위 세대를 바꿔 이전 증거를 재사용하지 못한다")
    void rejectsChangingAbsenceGeneration() {
      // given
      store.requireAbsenceVerification(R1, FINGERPRINT);
      store.recordAbsenceValidation(
          R1, new RunStore.AbsenceValidation(FINGERPRINT, "candidate-token", Set.of("outside")));
      // when·then
      assertThatThrownBy(() -> store.requireAbsenceVerification(R1, "different-generation"))
          .isInstanceOf(RunStateException.class);
    }

    @Test
    @DisplayName("검증을 요구하지 않은 기존 run은 추가 검증 조건이 없다")
    void legacyRunHasNoAbsenceRequirement() {
      // when
      var requirement = store.absenceRequirement(R1);
      // then
      assertThat(requirement).isEmpty();
    }

    @Test
    @DisplayName("요구한 적 없는 run에 부재 검증을 저장하지 않는다")
    void rejectsValidationWithoutRequirement() {
      // when·then
      assertThatThrownBy(
              () ->
                  store.recordAbsenceValidation(
                      R1,
                      new RunStore.AbsenceValidation(
                          FINGERPRINT, "candidate-token", Set.of("outside"))))
          .isInstanceOf(RunStateException.class);
    }

    @Test
    @DisplayName("새 run은 이전 run의 검증 결과를 승계하지 않는다")
    void validationDoesNotCarryToNewRun() {
      // given
      store.requireAbsenceVerification(R1, FINGERPRINT);
      store.recordAbsenceValidation(
          R1, new RunStore.AbsenceValidation(FINGERPRINT, "candidate-token", Set.of("outside")));
      store.start(NEW, SOURCE, RESTART);
      // when
      var validation = store.absenceValidation(NEW);
      // then
      assertThat(validation).isEmpty();
    }

    @Test
    @DisplayName("끝난 run의 검증 결과는 뒤늦게 바꾸지 못한다")
    void finishedRunRejectsValidation() {
      // given
      store.requireAbsenceVerification(R1, FINGERPRINT);
      finishAs(R1, RunStatus.SUCCEEDED, FINISHED);
      // when·then
      assertThatThrownBy(
              () ->
                  store.recordAbsenceValidation(
                      R1,
                      new RunStore.AbsenceValidation(
                          FINGERPRINT, "candidate-token", Set.of("outside"))))
          .isInstanceOf(RunStateException.class);
    }
  }
}
