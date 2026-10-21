package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  /** 격리 blob 데이터 읽기(공용 blob 저장소). */
  protected abstract Optional<byte[]> storedBlob(String sha256);

  @BeforeEach
  void setUpContract() {
    store = newStore();
  }

  CollectionRun finishAs(RunId id, RunStatus status, Instant at) {
    return store.finish(
        id,
        status,
        CollectionCoverage.INCREMENTAL,
        at,
        status == RunStatus.SUCCEEDED ? 0 : 1,
        Optional.empty(),
        Optional.empty(),
        0);
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
        RunStatus.PARTIALLY_SUCCEEDED,
        CollectionCoverage.INCREMENTAL,
        T0.plusSeconds(1),
        1,
        Optional.of("rate_limited"),
        Optional.of("429"),
        0);

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
              RunStatus.FAILED,
              CollectionCoverage.INCREMENTAL,
              FINISHED,
              1,
              Optional.of("rate_limited"),
              Optional.of("429"),
              0);

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
          RunStatus.SUCCEEDED,
          CollectionCoverage.COMPLETE_SNAPSHOT,
          FINISHED,
          0,
          Optional.empty(),
          Optional.empty(),
          4);
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
}
