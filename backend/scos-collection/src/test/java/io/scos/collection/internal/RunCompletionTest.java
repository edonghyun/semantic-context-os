package io.scos.collection.internal;

import static io.scos.collection.internal.RawItems.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * F5·불변식 6의 판단 로직을 메모리 포트로: 삭제(reconcile)는 succeeded + complete_snapshot일 때만, 점유 검사는 succeeded일 때만,
 * 투영 요청은 실제로 지운 것이 있을 때만. 원자성은 PostgresRunCompletionTest.
 */
@DisplayName("수집 run 마감 규칙 — reconcile은 성공한 완전 스냅샷만(F5·불변식 6)")
class RunCompletionTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final RunId RUN = new RunId("run-1");
  static final Duration TTL = Duration.ofSeconds(30);

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  MemoryRunStore runs = new MemoryRunStore();
  MemoryRawItemStore rawItems = new MemoryRawItemStore();
  MemoryOutboxPort outbox = new MemoryOutboxPort();
  MemorySourceChangeLog changes = new MemorySourceChangeLog();
  RunCompletion completion =
      new RunCompletion(
          TransactionOperations.withoutTransaction(),
          claims,
          runs,
          rawItems,
          outbox,
          changes,
          new SnapshotShrinkGuard(
              new MemorySourceInstanceStore(),
              new MemorySnapshotGuardStore(),
              SnapshotShrinkLimits.DEFAULTS,
              TestMetrics.collection()));
  WorkClaim claim;

  /** 원본 a·b·c가 있고 이번 run은 a만 관측했다. */
  @BeforeEach
  void seedAndObserveOnlyA() {
    for (String id : List.of("a", "b", "c"))
      rawItems.apply(SOURCE, new RawMutation.Upsert(item(id).build()));
    claim =
        claims
            .tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: w1이 점유하지 못했다"));
    runs.start(RUN, SOURCE, clock.instant());
    runs.observe(RUN, List.of("a"));
  }

  /** succeeded + complete_snapshot이 아닌 완료 — 어느 것도 reconcile하지 않는다. */
  static final String OTHER_COMPLETIONS =
      """
      SUCCEEDED, INCREMENTAL
      SUCCEEDED_WITH_REJECTIONS, COMPLETE_SNAPSHOT
      PARTIALLY_SUCCEEDED, COMPLETE_SNAPSHOT
      FAILED, COMPLETE_SNAPSHOT
      CANCELLED, COMPLETE_SNAPSHOT
      """;

  RunCompletion.Input failed(Optional<String> errorKind, Optional<String> errorSummary) {
    return new RunCompletion.Input(
        claim,
        RUN,
        RunStatus.FAILED,
        CollectionCoverage.INCREMENTAL,
        clock.instant(),
        1,
        errorKind,
        errorSummary);
  }

  RunCompletion.Input input(RunStatus status, CollectionCoverage coverage) {
    return new RunCompletion.Input(
        claim, RUN, status, coverage, clock.instant(), 0, Optional.empty(), Optional.empty());
  }

  /** 이번 run을 succeeded + complete_snapshot으로 완료한다. */
  CollectionRun completeSucceededSnapshot() {
    return completion.complete(input(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT));
  }

  @Test
  @DisplayName("비성공 완료는 점유가 만료됐어도 기록된다")
  void failureIsRecordedWithoutLiveClaim() {
    // given
    clock.advance(TTL.plusSeconds(1));

    // when
    CollectionRun run =
        completion.complete(input(RunStatus.FAILED, CollectionCoverage.COMPLETE_SNAPSHOT));

    // then
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
  }

  @Test
  @DisplayName("run이 running이 아니면 RunStateException이다")
  void rejectsNonRunningRun() {
    // given
    completion.complete(input(RunStatus.FAILED, CollectionCoverage.INCREMENTAL));

    // when·then
    assertThatThrownBy(
            () -> completion.complete(input(RunStatus.FAILED, CollectionCoverage.INCREMENTAL)))
        .isInstanceOf(RunStateException.class);
  }

  @Test
  @DisplayName("오류 요약은 500자로 잘린다")
  void truncatesErrorSummary() {
    // when
    RunCompletion.Input in = failed(Optional.of("x"), Optional.of("e".repeat(600)));

    // then
    assertThat(in.errorSummary().orElseThrow()).hasSize(500);
  }

  @Test
  @DisplayName("running 상태로는 완료할 수 없다")
  void rejectsRunningStatus() {
    // when·then
    assertThatThrownBy(
            () ->
                new RunCompletion.Input(
                    claim,
                    RUN,
                    RunStatus.RUNNING,
                    CollectionCoverage.INCREMENTAL,
                    clock.instant(),
                    0,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("succeeded 완료(증분)는 변경 로그에 아무것도 남기지 않는다(F10)")
  void successAppendsNothing() {
    // when
    completion.complete(input(RunStatus.SUCCEEDED, CollectionCoverage.INCREMENTAL));

    // then
    assertThat(changes.all()).isEmpty();
  }

  @Nested
  @DisplayName("succeeded + complete_snapshot으로 완료하면")
  class SucceededSnapshot {
    @Test
    @DisplayName("관측되지 않은 b·c를 tombstone으로 바꾼다")
    void tombstonesUnobserved() {
      // when
      completeSucceededSnapshot();

      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(1);
      assertThat(rawItems.tombstoneCount(SOURCE)).isEqualTo(2);
    }

    @Test
    @DisplayName("스냅샷 완료를 지운 수와 함께 기록한다")
    void recordsSnapshotCompletion() {
      // when
      completeSucceededSnapshot();

      // then
      assertThat(runs.lastSnapshotCompletion(SOURCE))
          .contains(new RunStore.SnapshotCompletion(clock.instant(), 2));
    }

    @Test
    @DisplayName("투영을 요청한다")
    void requestsProjection() {
      // when
      completeSucceededSnapshot();

      // then
      assertThat(outbox.pending()).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("run은 reconcile로 지운 수와 함께 succeeded로 끝난다")
    void finishesRun() {
      // when
      CollectionRun run = completeSucceededSnapshot();

      // then
      assertThat(run)
          .extracting(CollectionRun::status, CollectionRun::deleted, CollectionRun::finishedAt)
          .containsExactly(RunStatus.SUCCEEDED, 2, Optional.of(clock.instant()));
    }

    @Test
    @DisplayName("reconcile로 지운 b·c만 snapshot_absent로 변경 로그에 남는다(F10)")
    void reconcileAppendsSnapshotAbsent() {
      // when
      completeSucceededSnapshot();

      // then
      assertThat(changes.all())
          .extracting(c -> c.kind().wire() + ":" + c.externalId().orElse("-"))
          .containsExactly("snapshot_absent:b", "snapshot_absent:c");
    }

    @Nested
    @DisplayName("b·c도 관측해 지울 것이 없으면")
    class NothingToReconcile {
      @BeforeEach
      void givenAllObserved() {
        runs.observe(RUN, List.of("b", "c"));
      }

      @Test
      @DisplayName("아무것도 지우지 않고, 스냅샷 완료(0)는 기록한다")
      void recordsEmptySnapshotCompletion() {
        // when
        completeSucceededSnapshot();

        // then
        assertThat(rawItems.tombstoneCount(SOURCE)).isZero();
        assertThat(runs.lastSnapshotCompletion(SOURCE))
            .contains(new RunStore.SnapshotCompletion(clock.instant(), 0));
      }

      @Test
      @DisplayName("투영은 요청하지 않는다")
      void nothingToReconcileRequestsNoProjection() {
        // when
        completeSucceededSnapshot();

        // then
        assertThat(outbox.pending()).isEmpty();
      }
    }
  }

  /**
   * 특성화(T15.0b): reconcile은 완료하는 run 자신의 관측만 본다 — 새 run 시작이 죽은 run의 관측을 지운다(TS sqlite-source-store
   * startRun·completeCollectionRun과 같다). 그래서 스냅샷 재개가 앞 페이지를 지우지 않는 것은 어댑터 몫이다: 스냅샷 coverage의 run은
   * 처음부터 다시 걷는다(Slack snapshot 모드는 체크포인트를 버리고, Tiro는 늘 첫 페이지부터, Notion은 초기 체크포인트에서만
   * complete_snapshot — TiroCollectionKillTest·SlackAdapterFixtureTest).
   */
  @Nested
  @DisplayName("죽은 run이 a를, 새 run이 c만 관측하고 succeeded 스냅샷으로 완료하면")
  class ResumedSnapshotAfterDeadRun {
    static final RunId RESUMED = new RunId("run-2");

    CollectionRun resumed;

    @BeforeEach
    void givenDeadRunThenResumedObservingOnlyC() {
      clock.advance(TTL.plusSeconds(1));
      WorkClaim next =
          claims
              .tryClaim(SOURCE, new OwnerId("w2"), TTL, clock.instant())
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: 만료 뒤 점유하지 못했다"));
      runs.start(RESUMED, SOURCE, clock.instant());
      runs.observe(RESUMED, List.of("c"));
      resumed =
          completion.complete(
              new RunCompletion.Input(
                  next,
                  RESUMED,
                  RunStatus.SUCCEEDED,
                  CollectionCoverage.COMPLETE_SNAPSHOT,
                  clock.instant(),
                  0,
                  Optional.empty(),
                  Optional.empty()));
    }

    @Test
    @DisplayName("죽은 run만 관측한 a도 tombstone이다")
    void deadRunObservationDoesNotProtect() {
      // then
      assertThat(rawItems.get(SOURCE, "a")).containsInstanceOf(RawRecord.Deleted.class);
    }

    @Test
    @DisplayName("새 run이 관측한 c는 active로 남는다")
    void keepsResumedObservation() {
      // then
      assertThat(rawItems.get(SOURCE, "c")).containsInstanceOf(RawRecord.Active.class);
    }

    @Test
    @DisplayName("새 run은 a·b 2건을 지운 것으로 끝난다")
    void resumedRunDeletesTwo() {
      // then
      assertThat(resumed.deleted()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("아무것도 관측하지 않은 새 run이 succeeded 스냅샷으로 완료하면(ADR-0028)")
  class EmptySnapshot {
    static final RunId EMPTY = new RunId("run-2");

    CollectionRun completed;

    @BeforeEach
    void givenEmptyRunCompleted() {
      runs.start(EMPTY, SOURCE, clock.instant());
      completed =
          completion.complete(
              new RunCompletion.Input(
                  claim,
                  EMPTY,
                  RunStatus.SUCCEEDED,
                  CollectionCoverage.COMPLETE_SNAPSHOT,
                  clock.instant(),
                  0,
                  Optional.empty(),
                  Optional.empty()));
    }

    @Test
    @DisplayName("TS와 달리 소스의 active a·b·c를 하나도 지우지 않는다")
    void keepsEverything() {
      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(3);
      assertThat(rawItems.tombstoneCount(SOURCE)).isZero();
    }

    @Test
    @DisplayName("run은 지운 것 없이 보호 사유가 붙은 succeeded로 끝난다")
    void finishesGuardedRunWithoutDeletes() {
      // then
      assertThat(completed)
          .extracting(CollectionRun::status, CollectionRun::deleted, CollectionRun::errorKind)
          .containsExactly(RunStatus.SUCCEEDED, 0, Optional.of("snapshot_shrink_guarded"));
    }
  }

  @Nested
  @DisplayName("succeeded + complete_snapshot이 아닌 완료면(불변식 6)")
  class OtherCompletions {
    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("원본을 지우지 않는다")
    void keepsItems(RunStatus status, CollectionCoverage coverage) {
      // when
      completion.complete(input(status, coverage));

      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("스냅샷 완료를 남기지 않는다")
    void recordsNoSnapshotCompletion(RunStatus status, CollectionCoverage coverage) {
      // when
      completion.complete(input(status, coverage));

      // then
      assertThat(runs.lastSnapshotCompletion(SOURCE)).isEmpty();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("투영을 요청하지 않는다")
    void requestsNoProjection(RunStatus status, CollectionCoverage coverage) {
      // when
      completion.complete(input(status, coverage));

      // then
      assertThat(outbox.pending()).isEmpty();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("run은 지운 것 없이 요청한 상태로 끝난다")
    void finishesRunWithoutDeletes(RunStatus status, CollectionCoverage coverage) {
      // when
      CollectionRun run = completion.complete(input(status, coverage));

      // then
      assertThat(run)
          .extracting(CollectionRun::status, CollectionRun::deleted)
          .containsExactly(status, 0);
    }
  }

  @Nested
  @DisplayName("점유가 만료된 뒤 succeeded로 완료하면")
  class SucceededAfterClaimLost {
    @BeforeEach
    void givenClaimExpired() {
      clock.advance(TTL.plusSeconds(1));
    }

    @Test
    @DisplayName("ClaimLostException이다")
    void succeededRequiresLiveClaim() {
      // when
      Throwable rejected = catchThrowable(RunCompletionTest.this::completeSucceededSnapshot);

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("원본을 지우지 않는다")
    void keepsItems() {
      // when
      var unused = catchThrowable(RunCompletionTest.this::completeSucceededSnapshot);

      // then
      assertThat(rawItems.count(SOURCE)).isEqualTo(3);
    }

    @Test
    @DisplayName("run을 마감하지 않는다")
    void leavesRunRunning() {
      // when
      var unused = catchThrowable(RunCompletionTest.this::completeSucceededSnapshot);

      // then
      assertThat(runs.get(RUN).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }
  }

  @Nested
  @DisplayName("failed로 완료하면(F10)")
  class IncompleteChange {
    void completeFailed() {
      completion.complete(failed(Optional.of("storage_failed"), Optional.of("lock")));
    }

    /** 변경 로그의 유일한 변경. 한 건이 아니면 given 준비 실패로 멈춘다(건수는 appendsCollectionIncomplete의 몫). */
    SourceChangeLog.Change onlyChange() {
      List<SourceChangeLog.Change> all = changes.all();
      if (all.size() != 1) {
        throw new IllegalStateException("given 준비 실패: 변경이 한 건이 아니다 " + all);
      }
      return all.get(0);
    }

    @Test
    @DisplayName("문서 없는 collection_incomplete 한 건으로 남는다")
    void appendsCollectionIncomplete() {
      // when
      completeFailed();

      // then
      assertThat(changes.all())
          .singleElement()
          .extracting(SourceChangeLog.Change::kind, SourceChangeLog.Change::externalId)
          .containsExactly(SourceChangeLog.Kind.COLLECTION_INCOMPLETE, Optional.empty());
    }

    @Test
    @DisplayName("메타데이터에 status·errorKind를 남긴다")
    void recordsStatusAndErrorKind() {
      // when
      completeFailed();

      // then
      assertThat(onlyChange().metadataJson())
          .contains("{\"status\":\"failed\",\"errorKind\":\"storage_failed\"}");
    }

    @Test
    @DisplayName("완료 시각에 일어난 것으로 남는다")
    void occursAtCompletion() {
      // when
      completeFailed();

      // then
      assertThat(onlyChange().occurredAt()).isEqualTo(clock.instant());
    }
  }
}
