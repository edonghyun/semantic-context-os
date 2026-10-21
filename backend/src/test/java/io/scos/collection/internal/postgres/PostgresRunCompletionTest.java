package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.WorkClaim;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** F5·불변식 6: 삭제(reconcile)의 근거는 succeeded + complete_snapshot인 run의 관측 집합뿐이다. */
@DisplayName("reconcile 근거는 succeeded + complete_snapshot의 관측뿐이다(F5·불변식 6)")
@Tag("db")
class PostgresRunCompletionTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Duration TTL = Duration.ofSeconds(30);
  DataSource ds;
  PostgresCollectionConfiguration.Adapters adapters;
  MutableClock clock;
  PageCommit pageCommit;
  RunCompletion completion;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    adapters = PostgresCollectionConfiguration.adapters(ds);
    clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    pageCommit =
        new PageCommit(
            tx,
            adapters.claims(),
            adapters.runs(),
            adapters.rawItems(),
            adapters.checkpoints(),
            adapters.outbox(),
            clock,
            TestMetrics.collection(),
            adapters.changes(),
            new Progress(clock));
    completion = completion(adapters.changes());
  }

  RunCompletion completion(SourceChangeLog changes) {
    return new RunCompletion(
        new TransactionTemplate(new DataSourceTransactionManager(ds)),
        adapters.claims(),
        adapters.runs(),
        adapters.rawItems(),
        adapters.outbox(),
        changes,
        new SnapshotShrinkGuard(
            adapters.sources(),
            adapters.guards(),
            SnapshotShrinkLimits.DEFAULTS,
            TestMetrics.collection()));
  }

  CollectionPage page(String... ids) {
    return CollectionPage.ofItems(
        Arrays.stream(ids).map(id -> RawItem.minimal(id, "v1", "{}", clock.instant())).toList(),
        new Checkpoint(1, "{\"after\":\"" + ids[ids.length - 1] + "\"}"));
  }

  /** succeeded + complete_snapshot이 아닌 완료 — 어느 것도 reconcile하지 않는다. */
  static final String OTHER_COMPLETIONS =
      """
      SUCCEEDED, INCREMENTAL
      PARTIALLY_SUCCEEDED, COMPLETE_SNAPSHOT
      FAILED, COMPLETE_SNAPSHOT
      CANCELLED, COMPLETE_SNAPSHOT
      SUCCEEDED_WITH_REJECTIONS, COMPLETE_SNAPSHOT
      """;

  /** 이전 run이 a·b·c를 적재해 둔 상태에서 새 run을 시작하고 a만 관측한다. */
  WorkClaim seedAndObserveOnlyA(RunId run) {
    WorkClaim first =
        adapters.claims().tryClaim(SOURCE, new OwnerId("w0"), TTL, clock.instant()).orElseThrow();
    RunId r0 = new RunId("r0");
    adapters.runs().start(r0, SOURCE, clock.instant());
    pageCommit.commit(first, r0, 0, page("a", "b", "c"));
    completion.complete(
        new RunCompletion.Input(
            first,
            r0,
            RunStatus.SUCCEEDED,
            CollectionCoverage.INCREMENTAL,
            clock.instant(),
            0,
            Optional.empty(),
            Optional.empty()));
    adapters.claims().release(first, clock.instant());
    WorkClaim claim =
        adapters.claims().tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant()).orElseThrow();
    adapters.runs().start(run, SOURCE, clock.instant());
    pageCommit.commit(claim, run, 1, page("a"));
    adapters.outbox().complete(SOURCE, adapters.outbox().request(SOURCE).orElseThrow().version());
    if (adapters.rawItems().count(SOURCE) != 3
        || !adapters.runs().observations(run).equals(Set.of("a"))
        || !adapters.outbox().pending().isEmpty())
      throw new IllegalStateException("given 준비 실패: 원본 a·b·c active, 관측 {a}, 투영 요청 없음이어야 한다");
    return claim;
  }

  RunId run = new RunId("r1");
  WorkClaim claim;

  void givenOnlyAObserved() {
    claim = seedAndObserveOnlyA(run);
  }

  CollectionRun complete(RunStatus status, CollectionCoverage coverage) {
    return completion.complete(
        new RunCompletion.Input(
            claim,
            run,
            status,
            coverage,
            clock.instant(),
            status.isSuccess() ? 0 : 1,
            Optional.empty(),
            Optional.empty()));
  }

  @Nested
  @DisplayName("a·b·c 중 a만 관측한 run을 succeeded + complete_snapshot으로 완료하면")
  class SucceededSnapshot {
    @BeforeEach
    void givenObserved() {
      givenOnlyAObserved();
    }

    @Test
    @DisplayName("run은 지운 수와 함께 succeeded로 끝난다")
    void finishesRun() {
      // when
      CollectionRun done = complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT);

      // then
      assertThat(done)
          .extracting(CollectionRun::status, CollectionRun::deleted)
          .containsExactly(RunStatus.SUCCEEDED, 2);
    }

    @Test
    @DisplayName("관측되지 않은 b·c를 tombstone으로 바꾼다")
    void tombstonesUnobserved() {
      // when
      complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT);

      // then
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(1);
      assertThat(adapters.rawItems().get(SOURCE, "b").orElseThrow())
          .isInstanceOf(RawRecord.Deleted.class);
    }

    @Test
    @DisplayName("스냅샷 완료를 지운 수와 함께 기록한다")
    void recordsSnapshotCompletion() {
      // when
      complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT);

      // then
      assertThat(adapters.runs().lastSnapshotCompletion(SOURCE))
          .hasValueSatisfying(s -> assertThat(s.deletionCount()).isEqualTo(2));
    }

    @Test
    @DisplayName("투영을 요청한다")
    void requestsProjection() {
      // when
      complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT);

      // then
      assertThat(adapters.outbox().pending()).containsExactly(SOURCE);
    }

    @Test
    @DisplayName("run의 관측 집합을 비운다")
    void clearsObservations() {
      // when
      complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT);

      // then
      assertThat(adapters.runs().observations(run)).isEmpty();
    }
  }

  @Nested
  @DisplayName("a만 관측한 run의 정리 완료가 마지막 쓰기(변경 로그)에서 실패하면")
  class SnapshotCompletionFailsLate {
    @BeforeEach
    void givenObservedAndFailedCompletion() {
      givenOnlyAObserved();
      Throwable thrown =
          catchThrowable(
              () ->
                  completion(new FailingChangeLog(adapters.changes()))
                      .complete(
                          new RunCompletion.Input(
                              claim,
                              run,
                              RunStatus.SUCCEEDED,
                              CollectionCoverage.COMPLETE_SNAPSHOT,
                              clock.instant(),
                              0,
                              Optional.empty(),
                              Optional.empty())));
      if (!(thrown instanceof IllegalStateException e)
          || !e.getMessage().equals(FailingChangeLog.MESSAGE))
        throw new IllegalStateException("given 준비 실패: 완료가 변경 로그에서 실패하지 않았다", thrown);
    }

    @Test
    @DisplayName("b·c의 tombstone이 롤백돼 a·b·c가 active로 남는다")
    void rollsBackTombstones() {
      // then
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(3);
    }

    @Test
    @DisplayName("스냅샷 완료를 남기지 않는다")
    void recordsNoSnapshotCompletion() {
      // then
      assertThat(adapters.runs().lastSnapshotCompletion(SOURCE)).isEmpty();
    }

    @Test
    @DisplayName("투영을 요청하지 않는다")
    void leavesNoProjectionRequest() {
      // then
      assertThat(adapters.outbox().pending()).isEmpty();
    }

    @Test
    @DisplayName("run은 running으로 남고 관측 {a}도 그대로다")
    void leavesRunOpen() {
      // then
      assertThat(adapters.runs().get(run).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
      assertThat(adapters.runs().observations(run)).containsExactly("a");
    }

    @Test
    @DisplayName("변경 로그에 snapshot_absent를 남기지 않는다")
    void leavesNoSnapshotAbsentChanges() {
      // then
      assertThat(adapters.changes().read(Optional.of(SOURCE), 0, 10).changes())
          .extracting(SourceChangeLog.Change::kind)
          .doesNotContain(SourceChangeLog.Kind.SNAPSHOT_ABSENT);
    }
  }

  @Nested
  @DisplayName("a만 관측한 run을 그 밖의 상태·범위 조합으로 완료하면")
  class OtherCompletions {
    @BeforeEach
    void givenObserved() {
      givenOnlyAObserved();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("run은 지운 것 없이 요청한 상태로 끝난다")
    void finishesRunWithoutDeletes(RunStatus status, CollectionCoverage coverage) {
      // when
      CollectionRun done = complete(status, coverage);

      // then
      assertThat(done)
          .extracting(CollectionRun::status, CollectionRun::deleted)
          .containsExactly(status, 0);
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("원본을 지우지 않는다")
    void keepsItems(RunStatus status, CollectionCoverage coverage) {
      // when
      complete(status, coverage);

      // then
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("스냅샷 완료를 남기지 않는다")
    void recordsNoSnapshotNorRequest(RunStatus status, CollectionCoverage coverage) {
      // when
      complete(status, coverage);

      // then
      assertThat(adapters.runs().lastSnapshotCompletion(SOURCE)).isEmpty();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(textBlock = OTHER_COMPLETIONS)
    @DisplayName("투영을 요청하지 않는다")
    void requestsNoProjection(RunStatus status, CollectionCoverage coverage) {
      // when
      complete(status, coverage);

      // then
      assertThat(adapters.outbox().pending()).isEmpty();
    }
  }

  @Nested
  @DisplayName("a만 관측한 run의 점유가 만료된 뒤 succeeded로 완료하면")
  class ExpiredLease {
    @BeforeEach
    void givenLeaseExpired() {
      givenOnlyAObserved();
      clock.advance(TTL.plusSeconds(1));
    }

    @Test
    @DisplayName("ClaimLostException이다")
    void successRequiresLiveLease() {
      // when·then
      assertThatThrownBy(() -> complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("거부되고, run을 마감하지 않는다")
    void rejectedSuccessChangesNothing() {
      // when
      Throwable rejected =
          catchThrowable(() -> complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT));

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(adapters.runs().get(run).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("거부되고, 원본을 지우지 않는다")
    void rejectedSuccessKeepsItems() {
      // when
      Throwable rejected =
          catchThrowable(() -> complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT));

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(adapters.rawItems().count(SOURCE)).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("만료 뒤 succeeded가 거부된 run을 failed로 마감하면")
  class FailedAfterRejection {
    @BeforeEach
    void givenSuccessRejected() {
      givenOnlyAObserved();
      clock.advance(TTL.plusSeconds(1));
      Throwable rejected =
          catchThrowable(() -> complete(RunStatus.SUCCEEDED, CollectionCoverage.COMPLETE_SNAPSHOT));
      if (!(rejected instanceof ClaimLostException))
        throw new IllegalStateException(
            "given 준비 실패: 만료 뒤 succeeded 완료가 ClaimLostException으로 거부돼야 한다", rejected);
    }

    CollectionRun completeFailed() {
      return completion.complete(
          new RunCompletion.Input(
              claim,
              run,
              RunStatus.FAILED,
              CollectionCoverage.COMPLETE_SNAPSHOT,
              clock.instant(),
              1,
              Optional.of("claim_lost"),
              Optional.of("x".repeat(700))));
    }

    @Test
    @DisplayName("오류 종류와 함께 failed로 기록된다")
    void recordsFailure() {
      // when
      CollectionRun failed = completeFailed();

      // then
      assertThat(failed)
          .extracting(CollectionRun::status, CollectionRun::errorKind)
          .containsExactly(RunStatus.FAILED, Optional.of("claim_lost"));
    }

    @Test
    @DisplayName("오류 요약은 500자로 잘려 저장된다")
    void truncatesErrorSummary() {
      // when
      CollectionRun failed = completeFailed();

      // then
      assertThat(failed.errorSummary().orElseThrow()).hasSize(500);
    }
  }
}
