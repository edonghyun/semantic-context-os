package io.scos.collection.internal;

import static io.scos.collection.internal.SupervisorScenario.Collect.ALREADY_RUNNING;
import static io.scos.collection.internal.SupervisorScenario.Collect.BREAKS_NEXT_JUDGEMENT;
import static io.scos.collection.internal.SupervisorScenario.Collect.CANCELS_CYCLE;
import static io.scos.collection.internal.SupervisorScenario.Collect.FINISHES_WITH_ERRORS;
import static io.scos.collection.internal.SupervisorScenario.Collect.THROWS;
import static io.scos.collection.internal.SupervisorScenario.INJECTED;
import static io.scos.collection.internal.SupervisorScenario.INTERVAL;
import static io.scos.collection.internal.SupervisorScenario.JUDGEMENT_FAILURE;
import static io.scos.collection.internal.SupervisorScenario.RETRY_BASE;
import static io.scos.collection.internal.SupervisorScenario.SNAPSHOT_INTERVAL;
import static io.scos.collection.internal.SupervisorScenario.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.SourceSupervisor.Attempt;
import io.scos.collection.internal.SourceSupervisor.AttemptOutcome;
import io.scos.collection.internal.SourceSupervisor.Cycle;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F11 감독 정책(ADR-0020 §1): due 판정·backoff·스냅샷 모드·worker 수·소스별 격리·취소. 메모리 포트 + 가짜 시계. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("수집 감독은 due인 소스만 정한 수의 worker로 수집한다(F11, ADR-0020 §1)")
class SourceSupervisorTest {
  final SupervisorScenario scenario = new SupervisorScenario();

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  /** 사이클의 시도 수가 준비한 due 수와 다르면 given 준비 실패로 멈춘다(제출 수 단언이 엉뚱한 이유로 실패하지 않게). */
  static void requireAttempts(Cycle cycle, int due) {
    if (cycle.attempts().size() != due) {
      throw new IllegalStateException(
          "given 준비 실패: due " + due + "개를 시도하지 않았다 " + cycle.attempts());
    }
  }

  @Test
  @DisplayName("due 소스는 저장소가 준 순서가 아니라 id 순으로 시도한다")
  void attemptsInIdOrder() {
    // given
    scenario.givenSource("a");
    scenario.givenSource("b");
    scenario.listedInReverse().concurrency(1);

    // when
    Cycle cycle = scenario.runCycle();

    // then
    assertThat(cycle.attempts()).extracting(a -> a.source().value()).containsExactly("a", "b");
  }

  @ParameterizedTest(name = "최근 실패 {0}회 → {1}")
  @CsvSource({
    "0, PT30S",
    "1, PT30S",
    "2, PT60S",
    "3, PT120S",
    "4, PT240S",
    "5, PT300S",
    "20, PT300S"
  })
  @DisplayName("재시도 지연은 retryMax까지 retryBase·2^(실패−1)이다")
  void retryDelayGrowsAndCaps(int failures, Duration delay) {
    // when
    Duration retryDelay = scenario.retryDelay(failures);

    // then
    assertThat(retryDelay).isEqualTo(delay);
  }

  @Test
  @DisplayName("수집 보고에 오류가 있으면 Collected 시도도 실패로 센다")
  void collectedWithErrorsCountsAsFailed() {
    // given
    scenario.givenSource("a", FINISHES_WITH_ERRORS);

    // when
    Cycle cycle = scenario.runCycle();

    // then
    Attempt attempt = cycle.attempts().get(0);
    assertThat(attempt.outcome()).isInstanceOf(AttemptOutcome.Collected.class);
    assertThat(attempt.failed()).isTrue();
  }

  @Test
  @DisplayName("사이클 중 토큰이 취소되면 남은 due 소스를 집지 않는다")
  void cancellationStopsDispatch() {
    // given
    SourceInstanceId first = scenario.givenSource("a", CANCELS_CYCLE);
    scenario.givenSource("b");
    scenario.concurrency(1);

    // when
    Cycle cycle = scenario.runCycle();

    // then
    assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(first);
  }

  @Nested
  @DisplayName("마지막 run이 성공한 소스가 있으면")
  class LastRunSucceeded {
    static final Instant SUCCEEDED_AT = T0.minusSeconds(100);

    SourceInstanceId done;

    @BeforeEach
    void givenSucceededSource() {
      done = scenario.givenSource("b-done");
      scenario.givenFinished(done, RunStatus.SUCCEEDED, SUCCEEDED_AT);
    }

    @Test
    @DisplayName("run 기록이 없는 소스만 시도한다")
    void attemptsOnlySourcesWithoutRuns() {
      // given
      SourceInstanceId fresh = scenario.givenSource("a-fresh");

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(fresh);
    }

    @Test
    @DisplayName("성공한 소스는 시작 시각 + interval까지 미뤄진다")
    void successWaitsInterval() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.deferred()).containsEntry(done, SUCCEEDED_AT.plus(INTERVAL));
    }
  }

  @Nested
  @DisplayName("running으로 남은 run(죽은 프로세스)이 재시도 지연을 넘겼으면")
  class StaleRunningRun {
    SourceInstanceId source;

    @BeforeEach
    void givenStaleRunningRun() {
      source = scenario.givenSource("a");
      scenario.givenRunningSince(source, T0.minus(RETRY_BASE).minusSeconds(1));
    }

    @Test
    @DisplayName("재시도 지연 규칙으로 due가 되어 시도한다")
    void staleRunningRunIsRetried() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(source);
    }

    @Test
    @DisplayName("재시도는 수집으로 끝난다")
    void staleRunningRunRetryCollects() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts())
          .singleElement()
          .extracting(Attempt::outcome)
          .isInstanceOf(AttemptOutcome.Collected.class);
    }
  }

  @Nested
  @DisplayName("마지막 run이 비성공으로 끝났으면")
  class AfterNonSuccess {
    static final Instant FAILED_AT = T0.minusSeconds(10);
    static final Duration DELAY_AFTER_THREE_FAILURES = Duration.ofMinutes(2);

    SourceInstanceId source;

    @BeforeEach
    void givenSource() {
      source = scenario.givenSource("a");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"FAILED", "PARTIALLY_SUCCEEDED", "CANCELLED"})
    @DisplayName("시작 시각 + 첫 재시도 지연(30s)까지 미뤄진다")
    void deferredByFirstRetryDelay(RunStatus status) {
      // given
      scenario.givenFinished(source, status, FAILED_AT);

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.deferred()).containsEntry(source, FAILED_AT.plus(RETRY_BASE));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"FAILED", "PARTIALLY_SUCCEEDED", "CANCELLED"})
    @DisplayName("재시도 지연이 끝나는 시각에 다시 시도한다")
    void retriedWhenDelayEnds(RunStatus status) {
      // given
      scenario.givenFinished(source, status, FAILED_AT);
      scenario.advanceClock(Duration.between(T0, FAILED_AT.plus(RETRY_BASE)));

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(source);
    }

    @Test
    @DisplayName("연속 실패 3회 뒤 지연은 120s다")
    void consecutiveFailuresDoubleDelay() {
      // given
      scenario.givenFinished(source, RunStatus.FAILED, T0.minusSeconds(300));
      scenario.givenFinished(source, RunStatus.FAILED, T0.minusSeconds(200));
      scenario.givenFinished(source, RunStatus.FAILED, T0.minusSeconds(100));

      // when
      Optional<Instant> next = scenario.nextEligibleAt(source);

      // then
      assertThat(next).contains(T0.minusSeconds(100).plus(DELAY_AFTER_THREE_FAILURES));
    }
  }

  @Nested
  @DisplayName("다른 인스턴스의 점유가 있으면")
  class ClaimedElsewhere {
    static final Duration CLAIM_TTL = Duration.ofMinutes(3);
    static final Duration DEAD_CLAIM_TTL = Duration.ofSeconds(30);
    static final Instant RECENT_SUCCESS = T0.minusSeconds(10);

    SourceInstanceId source;

    @BeforeEach
    void givenSource() {
      source = scenario.givenSource("a");
    }

    /** 오래전에 만료된 점유가 남은 채 최근 성공한 소스. */
    void givenExpiredClaimAfterRecentSuccess() {
      scenario.givenClaimedElsewhere(source, DEAD_CLAIM_TTL, T0.minusSeconds(100));
      scenario.givenFinished(source, RunStatus.SUCCEEDED, RECENT_SUCCESS);
    }

    @Test
    @DisplayName("live 점유인 동안은 시도하지 않는다")
    void liveClaimIsNotAttempted() {
      // given
      scenario.givenClaimedElsewhere(source, CLAIM_TTL, T0);

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).isEmpty();
    }

    @Test
    @DisplayName("live 점유는 만료 시각까지 미룬다")
    void liveClaimDefersUntilExpiry() {
      // given
      WorkClaim claim = scenario.givenClaimedElsewhere(source, CLAIM_TTL, T0);

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.deferred()).containsEntry(source, claim.expiresAt());
    }

    @Test
    @DisplayName("점유가 만료되면 시도한다")
    void attemptsAfterClaimExpires() {
      // given
      scenario.givenClaimedElsewhere(source, CLAIM_TTL, T0);
      scenario.advanceClock(CLAIM_TTL.plusSeconds(1));

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).hasSize(1);
    }

    @Test
    @DisplayName("이미 만료된 점유 대신 성공 run의 interval까지 미룬다")
    void expiredClaimIsIgnored() {
      // given
      givenExpiredClaimAfterRecentSuccess();

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.deferred()).containsEntry(source, RECENT_SUCCESS.plus(INTERVAL));
    }

    @Test
    @DisplayName("이미 만료된 점유가 있어도 interval 안이면 시도하지 않는다")
    void expiredClaimWithinIntervalIsNotAttempted() {
      // given
      givenExpiredClaimAfterRecentSuccess();

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).isEmpty();
    }
  }

  @Nested
  @DisplayName("스냅샷 간격(1h)이 있을 때")
  class SnapshotInterval {
    /** interval은 지났지만 스냅샷 간격(1h)은 아직인 시점까지. */
    static final Duration PAST_INTERVAL = INTERVAL.plusSeconds(1);

    static final Instant COMPLETED = T0.minus(Duration.ofMinutes(10));

    /** 완료 + 간격(1h)을 1분 넘긴 시각. */
    static final Instant PAST_INTERVAL_FROM_COMPLETION = T0.plus(Duration.ofMinutes(51));

    SourceInstanceId source;

    @BeforeEach
    void givenSourceWithSnapshotInterval() {
      source = scenario.givenSource("a");
      scenario.snapshotEvery(SNAPSHOT_INTERVAL);
    }

    /** T0에 첫 사이클(스냅샷)을 돌리고 interval이 지났다. */
    void givenSnapshotAttemptedThenPastInterval() {
      scenario.runCycle();
      scenario.advanceClock(PAST_INTERVAL);
    }

    @Test
    @DisplayName("완료·시도 기록이 없는 첫 사이클은 스냅샷이다")
    void firstCycleIsSnapshot() {
      // when
      Cycle first = scenario.runCycle();

      // then
      assertThat(first.attempts().get(0).mode()).isEqualTo(CollectionMode.SNAPSHOT);
    }

    @Test
    @DisplayName("스냅샷 시도 뒤 간격 전 사이클은 증분이다")
    void incrementalWithinInterval() {
      // given
      givenSnapshotAttemptedThenPastInterval();

      // when
      Cycle second = scenario.runCycle();

      // then
      assertThat(second.attempts().get(0).mode()).isEqualTo(CollectionMode.INCREMENTAL);
    }

    @Test
    @DisplayName("간격이 지나면 다시 스냅샷이다")
    void snapshotAgainAfterInterval() {
      // given: 스냅샷 뒤 증분 사이클까지 돌고 스냅샷 간격이 지났다
      givenSnapshotAttemptedThenPastInterval();
      scenario.runCycle();
      scenario.advanceClock(SNAPSHOT_INTERVAL);

      // when
      Cycle third = scenario.runCycle();

      // then
      assertThat(third.attempts().get(0).mode()).isEqualTo(CollectionMode.SNAPSHOT);
    }

    @Test
    @DisplayName("마지막 complete snapshot 완료 뒤 간격 전에는 증분이다")
    void completedSnapshotCountsAsBaseline() {
      // given
      scenario.givenSnapshotCompletedAt(source, COMPLETED);

      // when
      CollectionMode mode = scenario.modeAt(source, T0);

      // then
      assertThat(mode).isEqualTo(CollectionMode.INCREMENTAL);
    }

    @Test
    @DisplayName("마지막 complete snapshot 완료 뒤 간격이 지나면 스냅샷이다")
    void snapshotAfterCompletedBaseline() {
      // given
      scenario.givenSnapshotCompletedAt(source, COMPLETED);

      // when
      CollectionMode mode = scenario.modeAt(source, PAST_INTERVAL_FROM_COMPLETION);

      // then
      assertThat(mode).isEqualTo(CollectionMode.SNAPSHOT);
    }
  }

  @Nested
  @DisplayName("T0에 스냅샷을 시도한 소스에 완료 기록이 있으면")
  class SnapshotBaseline {
    static final Instant ATTEMPT_PLUS_30M = T0.plus(Duration.ofMinutes(30));
    static final Instant EARLIER_COMPLETION = T0.minus(Duration.ofHours(2));

    /** 늦은 완료(T0+30m) + 간격 안. */
    static final Instant WITHIN_LATER_BASELINE = T0.plus(Duration.ofMinutes(61));

    /** 늦은 완료(T0+30m) + 간격 밖. */
    static final Instant PAST_LATER_BASELINE = T0.plus(Duration.ofMinutes(91));

    SourceInstanceId source;

    @BeforeEach
    void givenSnapshotAttemptedAtT0() {
      source = scenario.givenSource("a");
      scenario.snapshotEvery(SNAPSHOT_INTERVAL);
      Cycle first = scenario.runCycle();
      if (first.attempts().isEmpty() || first.attempts().get(0).mode() != CollectionMode.SNAPSHOT) {
        throw new IllegalStateException("given 준비 실패: T0에 스냅샷을 시도하지 않았다 " + first.attempts());
      }
    }

    @Test
    @DisplayName("완료가 시도보다 이르면 시도 시각 + 간격 전에는 증분이다")
    void earlierCompletionUsesAttempt() {
      // given
      scenario.givenSnapshotCompletedAt(source, EARLIER_COMPLETION);

      // when
      CollectionMode mode = scenario.modeAt(source, ATTEMPT_PLUS_30M);

      // then
      assertThat(mode).isEqualTo(CollectionMode.INCREMENTAL);
    }

    @Test
    @DisplayName("완료가 시도보다 늦으면 완료 시각 + 간격 전에는 증분이다")
    void laterCompletionUsesCompletion() {
      // given
      scenario.givenSnapshotCompletedAt(source, ATTEMPT_PLUS_30M);

      // when
      CollectionMode mode = scenario.modeAt(source, WITHIN_LATER_BASELINE);

      // then
      assertThat(mode).isEqualTo(CollectionMode.INCREMENTAL);
    }

    @Test
    @DisplayName("늦은 쪽 + 간격이 지나면 스냅샷이다")
    void snapshotAfterLaterBaseline() {
      // given
      scenario.givenSnapshotCompletedAt(source, ATTEMPT_PLUS_30M);

      // when
      CollectionMode mode = scenario.modeAt(source, PAST_LATER_BASELINE);

      // then
      assertThat(mode).isEqualTo(CollectionMode.SNAPSHOT);
    }
  }

  @Nested
  @DisplayName("concurrency 2에 오래 걸리는 due 소스가 3개면")
  class BoundedConcurrency {
    List<SourceInstanceId> ids;

    @BeforeEach
    void givenThreeHeldCollections() {
      ids =
          List.of(scenario.givenSource("a"), scenario.givenSource("b"), scenario.givenSource("c"));
      scenario.collectionsHeld();
    }

    @Test
    @DisplayName("동시에 둘만 수집한다")
    void collectsTwoAtOnce() throws Exception {
      // given: 사이클이 돌고 둘이 수집 중이다(셋째는 아직 아니다)
      Future<Cycle> cycle = scenario.startCycleInBackground();
      scenario.awaitCollectionsInFlight(2);
      if (scenario.collectionsInFlight() != 2) {
        throw new IllegalStateException(
            "given 준비 실패: 동시 수집이 2가 아니다 " + scenario.collectionsInFlight());
      }

      // when
      scenario.releaseCollections();
      scenario.finish(cycle);

      // then
      assertThat(scenario.maxCollectionsInFlight()).isEqualTo(2);
    }

    @Test
    @DisplayName("셋째는 worker가 비면 집어 셋 다 id 순으로 시도한다")
    void thirdWaitsForFreeWorker() throws Exception {
      // given
      Future<Cycle> cycle = scenario.startCycleInBackground();
      scenario.awaitCollectionsInFlight(2);

      // when
      scenario.releaseCollections();
      Cycle done = scenario.finish(cycle);

      // then
      assertThat(done.attempts()).extracting(Attempt::source).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("거부·누락 없이 셋 다 수집한다")
    void collectsAll() throws Exception {
      // given
      Future<Cycle> cycle = scenario.startCycleInBackground();
      scenario.awaitCollectionsInFlight(2);

      // when
      scenario.releaseCollections();
      scenario.finish(cycle);

      // then
      assertThat(scenario.collectedSources()).containsExactlyInAnyOrderElementsOf(ids);
    }
  }

  @Nested
  @DisplayName("수집이 붙잡혀 사이클이 열린 채 틱이 거듭되면")
  class TicksWhileCycleOpen {
    @Test
    @DisplayName("수집 중인 소스는 기록상 다시 due여도 또 집지 않는다")
    void inFlightSourceIsNotPickedTwice() throws Exception {
      // given: a는 run 기록이 남기 전이라 기록상 계속 due다. worker 자리는 하나 더 비어 있다
      SourceInstanceId a = scenario.givenSource("a");
      scenario.collectionsHeld();
      scenario.tick();
      scenario.awaitCollectionsInFlight(1);

      // when
      scenario.tick();
      scenario.releaseCollections();
      scenario.runCycle();

      // then
      assertThat(scenario.collections(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 수집은 concurrency를 넘지 않는다")
    void concurrencyCapHoldsAcrossTicks() throws Exception {
      // given: due 3, concurrency 2 — 둘이 수집 중이다
      scenario.givenSource("a");
      scenario.givenSource("b");
      scenario.givenSource("c");
      scenario.collectionsHeld();
      scenario.tick();
      scenario.awaitCollectionsInFlight(2);

      // when
      scenario.tick();
      scenario.tick();
      scenario.releaseCollections();
      scenario.runCycle();

      // then
      assertThat(scenario.maxCollectionsInFlight()).isEqualTo(2);
    }

    @Test
    @DisplayName("열린 사이클은 시작 때 due였던 소스가 전부 끝나야 결과를 낸다")
    void openCycleReportsNothing() {
      // given
      scenario.givenSource("a");
      scenario.collectionsHeld();
      scenario.tick();
      scenario.awaitCollectionsInFlight(1);

      // when
      Optional<Cycle> cycle = scenario.tick();

      // then
      assertThat(cycle).isEmpty();
    }
  }

  @Nested
  @DisplayName("concurrency 2에서 사이클을 돌리면")
  class WorkerCount {
    @BeforeEach
    void givenThreeSources() {
      scenario.givenSource("a");
      scenario.givenSource("b");
      scenario.givenSource("c");
    }

    @Test
    @DisplayName("due 3이면 작업 2개를 제출한다")
    void submitsConcurrencyWhenMoreDue() {
      // when
      Cycle cycle = scenario.runCycle();
      requireAttempts(cycle, 3);

      // then
      assertThat(scenario.submittedWorkers()).isEqualTo(2);
    }

    @Test
    @DisplayName("due 1이면 작업 1개를 제출한다")
    void submitsDueWhenFewerDue() {
      // given: 첫 사이클 뒤 interval이 지났고, b·c는 그 시각에 성공했다
      scenario.runCycle();
      scenario.advanceClock(INTERVAL);
      scenario.givenFinished(new SourceInstanceId("b"), RunStatus.SUCCEEDED, T0.plus(INTERVAL));
      scenario.givenFinished(new SourceInstanceId("c"), RunStatus.SUCCEEDED, T0.plus(INTERVAL));
      scenario.resetSubmittedWorkers();

      // when
      Cycle cycle = scenario.runCycle();
      requireAttempts(cycle, 1);

      // then
      assertThat(scenario.submittedWorkers()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("한 사이클에 실패·경합·성공 소스가 섞이면")
  class MixedSources {
    SourceInstanceId bad;
    SourceInstanceId busy;
    SourceInstanceId good;

    @BeforeEach
    void givenMixedSources() {
      bad = scenario.givenSource("a-bad", THROWS);
      busy = scenario.givenSource("b-busy", ALREADY_RUNNING);
      good = scenario.givenSource("c-good");
    }

    @Test
    @DisplayName("셋 다 시도로 남는다")
    void allAttempted() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(bad, busy, good);
    }

    @Test
    @DisplayName("예외를 던진 소스는 원인 예외를 그대로 담은 실패 시도다")
    void exceptionBecomesFailedAttempt() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      Attempt attempt = cycle.attempts().get(0);
      assertThat(attempt.outcome())
          .isInstanceOfSatisfying(
              AttemptOutcome.Failed.class, f -> assertThat(f.cause()).isSameAs(INJECTED));
      assertThat(attempt.failed()).isTrue();
    }

    @Test
    @DisplayName("점유 경합은 실패로 세는 AlreadyRunning 시도다")
    void contentionIsFailedAttempt() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      Attempt attempt = cycle.attempts().get(1);
      assertThat(attempt.outcome()).isInstanceOf(AttemptOutcome.AlreadyRunning.class);
      assertThat(attempt.failed()).isTrue();
    }

    @Test
    @DisplayName("성공 소스는 성공한 Collected 시도다")
    void goodSourceSucceeds() {
      // when
      Cycle cycle = scenario.runCycle();

      // then
      Attempt attempt = cycle.attempts().get(2);
      assertThat(attempt.outcome())
          .isInstanceOfSatisfying(
              AttemptOutcome.Collected.class,
              c -> assertThat(c.report().status()).isEqualTo(RunStatus.SUCCEEDED));
      assertThat(attempt.failed()).isFalse();
    }

    @Test
    @DisplayName("실제로 수집된 것은 성공 소스뿐이다")
    void onlyGoodSourceCollected() {
      // when
      scenario.runCycle();

      // then
      assertThat(scenario.collectedSources()).containsOnly(good);
    }
  }

  @Nested
  @DisplayName("worker 합류를 기다리는 감독 호출 스레드가 인터럽트되면")
  class InterruptedCaller {
    SourceInstanceId source;

    @BeforeEach
    void givenHeldCollection() {
      source = scenario.givenSource("a");
      scenario.collectionsHeld();
    }

    @Test
    @DisplayName("worker가 끝나기 전에 돌아온다")
    void returnsWithoutWaiting() throws Exception {
      // when
      SupervisorScenario.InterruptedCycle interrupted = scenario.runCycleAndInterruptCaller();

      // then
      assertThat(interrupted.returned()).isTrue();
    }

    @Test
    @DisplayName("호출 스레드의 인터럽트 표시를 보존한다")
    void keepsInterruptFlag() throws Exception {
      // when
      SupervisorScenario.InterruptedCycle interrupted = scenario.runCycleAndInterruptCaller();

      // then
      assertThat(interrupted.callerStillInterrupted()).isTrue();
    }

    @Test
    @DisplayName("끝나지 않은 시도는 사이클 결과에 싣지 않는다")
    void omitsUnfinishedAttempts() throws Exception {
      // when
      SupervisorScenario.InterruptedCycle interrupted = scenario.runCycleAndInterruptCaller();

      // then
      assertThat(interrupted.cycle().attempts()).isEmpty();
    }

    @Test
    @DisplayName("끝나지 않았던 시도는 worker가 마치면 다음 사이클 결과에 실린다")
    void unfinishedAttemptJoinsNextCycle() throws Exception {
      // given: 사이클이 닫힌 뒤 worker가 수집을 마쳤다
      scenario.runCycleAndInterruptCaller();
      scenario.releaseCollections();
      scenario.awaitWorkersIdle();

      // when
      Cycle next = scenario.runCycle();

      // then
      assertThat(next.attempts()).extracting(Attempt::source).containsExactly(source);
    }
  }

  @Nested
  @DisplayName("concurrency 1에 a를 수집하는 동안 기다리던 b를 다른 인스턴스가 가져가면")
  class WaitingSourceTakenElsewhere {
    static final Duration CLAIM_TTL = Duration.ofMinutes(3);

    SourceInstanceId first;
    Future<Cycle> cycle;

    @BeforeEach
    void givenWaitingSourceClaimedElsewhere() {
      first = scenario.givenSource("a");
      SourceInstanceId waiting = scenario.givenSource("b");
      scenario.concurrency(1).collectionsHeld();
      cycle = scenario.startCycleInBackground();
      scenario.awaitCollectionsInFlight(1);
      scenario.givenClaimedElsewhere(waiting, CLAIM_TTL, T0);
    }

    @Test
    @DisplayName("a를 마친 worker가 다시 판정해 b를 기다리지 않고 사이클을 닫는다")
    void cycleEndsWithoutWaitingSource() throws Exception {
      // when
      scenario.releaseCollections();
      Cycle done = scenario.finish(cycle);

      // then
      assertThat(done.attempts()).extracting(Attempt::source).containsExactly(first);
    }
  }

  @Nested
  @DisplayName("concurrency 1에 b가 기다리는 동안 a를 마친 worker가 판정 중 저장소 오류로 끝나면")
  class WorkerDiesOnJudgement {
    static final Duration CLAIM_TTL = Duration.ofMinutes(3);

    SourceInstanceId first;
    SourceInstanceId waiting;
    Throwable rethrown;

    @BeforeEach
    void givenWorkerDiedAfterFirstSource() {
      first = scenario.givenSource("a", BREAKS_NEXT_JUDGEMENT);
      waiting = scenario.givenSource("b");
      scenario.concurrency(1);
      scenario.tick();
      scenario.awaitWorkersIdle();
      rethrown = catchThrowable(scenario::tick);
    }

    @Test
    @DisplayName("그 오류는 다음 틱의 호출 스레드에 원인과 함께 던져진다")
    void nextTickRethrowsCause() {
      // then
      assertThat(rethrown)
          .isInstanceOfSatisfying(
              IllegalStateException.class,
              e -> assertThat(e.getCause()).isSameAs(JUDGEMENT_FAILURE));
    }

    @Test
    @DisplayName("그 worker 자리는 다음 사이클에 다시 쓰여 기다리던 소스를 수집한다")
    void freedSlotCollectsWaitingSource() {
      // when
      scenario.runCycle();

      // then
      assertThat(scenario.collections(waiting)).isEqualTo(1);
    }

    @Test
    @DisplayName("기다리던 소스가 due가 아니게 되면 다음 사이클은 그 소스를 기다리지 않고 닫힌다")
    void cycleClosesWhenWaitingSourceIsNoLongerDue() {
      // given
      scenario.givenClaimedElsewhere(waiting, CLAIM_TTL, T0);

      // when
      Cycle cycle = scenario.runCycle();

      // then
      assertThat(cycle.attempts()).extracting(Attempt::source).containsExactly(first);
    }
  }

  @Test
  @DisplayName("실행기가 닫혀 띄우지 못한 worker 자리는 다음 틱에 다시 띄운다")
  void rejectedWorkerSlotIsReturned() {
    // given: due 2, concurrency 2
    scenario.givenSource("a");
    scenario.givenSource("b");
    scenario.givenWorkersShutDown();
    scenario.tick();

    // when
    scenario.tick();

    // then
    assertThat(scenario.submittedWorkers()).isEqualTo(4);
  }

  @Nested
  @DisplayName("감독 옵션(Options)을 만들면")
  class Options {
    static final Duration SMALLEST = Duration.ofSeconds(1);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "interval 0, PT0S, PT30S, PT300S, 1, , interval must be positive",
      "retryBase 0, PT900S, PT0S, PT300S, 1, , retryBase must be positive",
      "retryMax < retryBase, PT900S, PT300S, PT30S, 1, , retryMax must be >= retryBase",
      "concurrency 0, PT900S, PT30S, PT300S, 0, , concurrency must be >= 1",
      "snapshotInterval 0, PT900S, PT30S, PT300S, 1, PT0S, snapshotInterval must be positive"
    })
    @DisplayName("범위를 벗어난 항목은 그 이름을 밝혀 거부된다")
    void rejectsOutOfRange(
        String option,
        Duration interval,
        Duration retryBase,
        Duration retryMax,
        int concurrency,
        Duration snapshotInterval,
        String message) {
      // when·then
      assertThatThrownBy(
              () ->
                  new SourceSupervisor.Options(
                      interval,
                      retryBase,
                      retryMax,
                      concurrency,
                      Optional.ofNullable(snapshotInterval)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(message);
    }

    @Test
    @DisplayName("설정의 경계값은 받아들여진다")
    void acceptsBoundaries() {
      // when: 전부 양수, retryBase = retryMax, concurrency 1, 스냅샷 간격 1s
      SourceSupervisor.Options options =
          new SourceSupervisor.Options(SMALLEST, RETRY_BASE, RETRY_BASE, 1, Optional.of(SMALLEST));

      // then
      assertThat(options.concurrency()).isEqualTo(1);
      assertThat(options.snapshotInterval()).contains(SMALLEST);
    }
  }
}
