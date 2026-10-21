package io.scos.server;

import static io.scos.server.DaemonScenario.COLLECTOR_FAILURE;
import static io.scos.server.DaemonScenario.Collector.ALWAYS_FAILS;
import static io.scos.server.DaemonScenario.Collector.FAILS_FIRST_CALL;
import static io.scos.server.DaemonScenario.Collector.HELD_IN_FIRST_COLLECTION;
import static io.scos.server.DaemonScenario.Collector.STUCK_HOLDING_CLAIM;
import static io.scos.server.DaemonScenario.Collector.TAKES_SIX_MINUTES;
import static io.scos.server.DaemonScenario.Collector.TAKES_THREE_SECONDS;
import static io.scos.server.DaemonScenario.Collector.WAITS_ON_RATE_LIMITED_PROVIDER;
import static io.scos.server.DaemonScenario.FAST_POLL;
import static io.scos.server.DaemonScenario.MAINTENANCE_INTERVAL;
import static io.scos.server.DaemonScenario.STALL_AFTER;
import static io.scos.server.DaemonScenario.STORAGE_FAILURE;
import static io.scos.server.DaemonScenario.SupervisorStorage.FAILS;
import static io.scos.server.DaemonScenario.SupervisorStorage.FAILS_FATALLY;
import static io.scos.server.DaemonScenario.T0;
import static io.scos.server.DaemonScenario.WAIT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceSupervisor.Attempt;
import io.scos.collection.internal.SourceSupervisor.AttemptOutcome;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.BackgroundThread;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.health.contributor.Status;

/** 상주 데몬의 사이클·유지보수·종료·health 계약(ADR-0020 §1·§3). 사이클 지표 기록은 DaemonObserverTest. */
@DisplayName("상주 데몬은 사이클을 반복하고 stop·health 계약을 지킨다(ADR-0020 §1·§3)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CollectionDaemonTest {
  static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
  static final Duration BEYOND_STALL = STALL_AFTER.plusMinutes(1);

  final DaemonScenario scenario = new DaemonScenario();

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  @Nested
  @DisplayName("구성만 하고 시작하지 않은 상태에서")
  class BeforeStart {
    @BeforeEach
    void givenSource() {
      scenario.givenSource("a");
    }

    @Test
    @DisplayName("ready가 아니다")
    void notReadyBeforeStart() {
      // when
      boolean ready = scenario.daemon().ready();

      // then
      assertThat(ready).isFalse();
    }

    @Test
    @DisplayName("running이 아니다")
    void notRunningBeforeStart() {
      // when
      boolean running = scenario.daemon().isRunning();

      // then
      assertThat(running).isFalse();
    }

    @Test
    @DisplayName("phase는 가장 늦게 시작해 가장 먼저 멈추는 최대값이다")
    void hasHighestPhase() {
      // when
      int phase = scenario.daemon().getPhase();

      // then
      assertThat(phase).isEqualTo(Integer.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("소스 하나로 시작해 첫 사이클이 끝나면")
  class FirstCycle {
    SourceInstanceId source;

    @BeforeEach
    void givenSource() {
      source = scenario.givenSource("a");
    }

    @Test
    @DisplayName("ready다")
    void readyAfterFirstCycle() throws Exception {
      // when
      CollectionDaemon daemon = scenario.start();
      scenario.awaitFirstCycle();

      // then
      assertThat(daemon.ready()).isTrue();
    }

    @Test
    @DisplayName("끝난 사이클 수와 마지막 사이클의 끝 시각을 보고한다")
    void reportsCycleCountAndTime() throws Exception {
      // given: 수집이 3초 걸린다(시작 T0, 끝 T0+3s)
      scenario.collector(TAKES_THREE_SECONDS);

      // when
      CollectionDaemon daemon = scenario.start();
      scenario.awaitFirstCycle();

      // then
      assertThat(daemon.cycles()).isEqualTo(1);
      assertThat(daemon.lastCycleAt()).contains(T0.plusSeconds(3));
    }

    @Test
    @DisplayName("사이클 결과에는 due 소스의 시도가 있다")
    void outcomeHasDueAttempts() throws Exception {
      // when
      scenario.start();
      CollectionDaemon.CycleOutcome first = scenario.nextCycle();

      // then
      assertThat(first.collection().orElseThrow().attempts())
          .extracting(Attempt::source)
          .containsExactly(source);
    }

    @Test
    @DisplayName("수집기에는 감독이 정한 수집 모드가 전달된다")
    void passesSupervisorMode() throws Exception {
      // when
      scenario.start();
      scenario.awaitFirstCycle();

      // then
      assertThat(scenario.modesSeen()).containsExactly(CollectionMode.INCREMENTAL);
    }

    @Test
    @DisplayName("due 투영 요청을 처리한 보고를 사이클 결과에 싣는다")
    void runsDueProjections() throws Exception {
      // given
      scenario.givenProjectionRequested(source);

      // when
      scenario.start();
      CollectionDaemon.CycleOutcome first = scenario.nextCycle();

      // then
      assertThat(first.projections())
          .extracting(ProjectionRun.Report::outcome)
          .containsExactly(ProjectionRun.Outcome.SKIPPED_UNKNOWN_KIND);
    }

    @Test
    @DisplayName("투영 보고는 그것을 처리한 사이클의 결과에만 실린다")
    void projectionReportedOnce() throws Exception {
      // given
      scenario.givenProjectionRequested(source);
      scenario.polling(FAST_POLL);

      // when
      scenario.start();
      scenario.nextCycle();
      CollectionDaemon.CycleOutcome second = scenario.nextCycle();

      // then
      assertThat(second.projections()).isEmpty();
    }

    @Test
    @DisplayName("처리한 투영 요청은 대기 목록에서 빠진다")
    void clearsDueProjections() throws Exception {
      // given
      scenario.givenProjectionRequested(source);

      // when
      scenario.start();
      scenario.nextCycle();

      // then
      assertThat(scenario.pendingProjections()).isEmpty();
    }

    @Test
    @DisplayName("마지막 진행 시각은 그 사이클의 끝 시각이다")
    void progressIsCycleEnd() throws Exception {
      // given: 수집이 3초 걸린다(시작 T0, 끝 T0+3s)
      scenario.collector(TAKES_THREE_SECONDS);

      // when: 사이클이 끝난 뒤 시간이 더 흐른다
      CollectionDaemon daemon = scenario.start();
      scenario.awaitFirstCycle();
      scenario.advanceClock(FIVE_MINUTES);

      // then
      assertThat(daemon.lastProgressAt()).contains(T0.plusSeconds(3));
    }
  }

  @Nested
  @DisplayName("첫 수집이 아직 끝나지 않았을 때")
  class DuringFirstCollection {
    CollectionDaemon daemon;

    @BeforeEach
    void givenDaemonHeldInFirstCollection() {
      scenario.givenSource("a");
      scenario.collector(HELD_IN_FIRST_COLLECTION);
      daemon = scenario.start();
      scenario.awaitCollectionEntered();
    }

    @Test
    @DisplayName("시간이 흘러도 마지막 진행 시각은 시작 시각이다")
    void progressIsStartTime() {
      // when
      scenario.advanceClock(FIVE_MINUTES);

      // then
      assertThat(daemon.lastProgressAt()).contains(T0);
    }

    @Test
    @DisplayName("루프는 살아 있다")
    void loopIsAlive() {
      // when
      boolean alive = daemon.loopAlive();

      // then
      assertThat(alive).isTrue();
    }
  }

  @Nested
  @DisplayName("첫 수집이 정체 기준보다 오래 붙잡혀 있으면")
  class HeldBeyondStall {
    SourceInstanceId source;

    @BeforeEach
    void givenFirstCollectionHeldBeyondStall() {
      source = scenario.givenSource("a");
      scenario.collector(HELD_IN_FIRST_COLLECTION);
      scenario.start();
      scenario.awaitCollectionEntered();
      scenario.advanceClock(BEYOND_STALL);
    }

    @Test
    @DisplayName("그 수집의 작업 진행 신호(페이지 커밋)가 있으면 health는 UP이다")
    void longCycleWithProgressIsUp() {
      // when
      scenario.reportPageCommit(source);

      // then
      assertThat(scenario.health()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("그동안 진행 신호가 없으면 health는 DOWN이다")
    void longCycleWithoutProgressIsDown() {
      // when
      Status health = scenario.health();

      // then
      assertThat(health).isEqualTo(Status.DOWN);
    }
  }

  @Test
  @DisplayName("지난 수집의 커밋은 붙잡힌 새 시도의 진행으로 치지 않는다")
  void earlierCommitDoesNotCountForNewAttempt() {
    // given: a의 커밋이 T0에 있었고, 데몬은 5분 뒤 시작해 a의 수집에 붙잡혔다
    SourceInstanceId source = scenario.givenSource("a");
    scenario.reportPageCommit(source);
    scenario.advanceClock(FIVE_MINUTES);
    scenario.collector(HELD_IN_FIRST_COLLECTION);
    CollectionDaemon daemon = scenario.start();
    scenario.awaitCollectionEntered();

    // when
    scenario.advanceClock(FIVE_MINUTES);

    // then
    assertThat(daemon.lastProgressAt()).contains(T0.plus(FIVE_MINUTES));
  }

  @Test
  @DisplayName("첫 틱이 투영에 붙잡혀 수집 중인 시도가 없어도 마지막 진행 시각은 시작 시각이다")
  void progressIsStartTimeWhileFirstTickProjects() {
    // given
    scenario.givenSlackSourceAwaitingProjection("slack-a");
    scenario.embedderWaitsOnRateLimitedProvider();
    CollectionDaemon daemon = scenario.start();
    scenario.awaitProviderCall();

    // when
    scenario.advanceClock(FIVE_MINUTES);

    // then
    assertThat(daemon.lastProgressAt()).contains(T0);
  }

  @Test
  @DisplayName("사이클이 열린 채 끝난 틱은 루프가 poll 대기 없이 다음 틱으로 가게 한다")
  void openCycleTickAsksForNextTick() {
    // given
    scenario.givenSource("a");
    scenario.collector(HELD_IN_FIRST_COLLECTION).polling(FAST_POLL);

    // when
    boolean delivered = scenario.tickWithoutLoop();

    // then
    assertThat(delivered).isFalse();
  }

  @Test
  @DisplayName("점유를 쥔 worker가 커밋 없이 멈추면 heartbeat가 성공해도 health는 DOWN이다")
  void heartbeatsWithoutCommitsAreStalled() {
    // given
    scenario.givenSource("a");
    scenario.collector(STUCK_HOLDING_CLAIM);
    scenario.start();
    scenario.awaitCollectionEntered();

    // when
    scenario.runOnlyHeartbeatsFor(BEYOND_STALL);

    // then
    assertThat(scenario.health()).isEqualTo(Status.DOWN);
  }

  @Nested
  @DisplayName("긴 소스가 worker 하나를 쥐고 있는 동안(concurrency 2, interval 15분)")
  class WhileLongSourceRuns {
    /** slack은 T0−10분에 성공했다 — T0+5분에 다시 due다. */
    static final Duration SLACK_LAST_RUN_AGO = Duration.ofMinutes(10);

    static final Duration PAST_SLACK_DUE = Duration.ofMinutes(6);

    SourceInstanceId drive;
    SourceInstanceId jira;
    SourceInstanceId slack;

    @BeforeEach
    void givenLongShortAndFrequentSources() {
      drive = scenario.givenSource("drive");
      jira = scenario.givenSource("jira");
      slack = scenario.givenSource("slack");
      scenario.givenSucceededAt(slack, T0.minus(SLACK_LAST_RUN_AGO));
      scenario.collector(drive, HELD_IN_FIRST_COLLECTION);
    }

    @Test
    @DisplayName("짧은 소스를 끝낸 worker는 그때 due가 된 잦은 소스를 바로 집는다")
    void freedWorkerPicksSourceThatBecameDue() {
      // given: jira 수집이 6분 걸려, 끝날 때 slack이 due다. poll(1h)은 테스트 안에 오지 않는다
      scenario.collector(jira, TAKES_SIX_MINUTES);
      scenario.start();

      // when
      scenario.awaitCollectionsFinished(slack, 1);

      // then
      assertThat(scenario.finishedCollections(drive)).isZero();
    }

    @Test
    @DisplayName("빈 worker가 있으면 잦은 소스는 due가 된 뒤의 poll에 다시 수집된다")
    void idleWorkerCollectsFrequentSourceWhenDue() {
      // given: jira는 바로 끝나 worker 하나가 비었다
      scenario.polling(FAST_POLL);
      scenario.start();
      scenario.awaitCollectionsFinished(jira, 1);

      // when: slack이 due가 된다
      scenario.advanceClock(PAST_SLACK_DUE);
      scenario.awaitCollectionsFinished(slack, 1);

      // then
      assertThat(scenario.finishedCollections(drive)).isZero();
    }

    @Nested
    @DisplayName("긴 수집이 정체 기준보다 오래 걸리는 동안 잦은 소스가 계속 성공하면")
    class LongSourceBeyondStall {
      @BeforeEach
      void givenFrequentSourceSucceededAfterStallWindow() {
        scenario.polling(FAST_POLL);
        scenario.start();
        scenario.awaitCollectionsFinished(jira, 1);
        scenario.advanceClock(BEYOND_STALL);
        scenario.awaitCollectionsFinished(slack, 1);
      }

      @Test
      @DisplayName("긴 수집에 진행 신호가 없으면 health는 DOWN이다")
      void stalledLongSourceIsNotMaskedByOthers() {
        // when
        Status health = scenario.health();

        // then
        assertThat(health).isEqualTo(Status.DOWN);
      }

      @Test
      @DisplayName("긴 수집이 페이지를 커밋하고 있으면 health는 UP이다")
      void progressingLongSourceIsUp() {
        // when
        scenario.reportPageCommit(drive);

        // then
        assertThat(scenario.health()).isEqualTo(Status.UP);
      }
    }
  }

  @Nested
  @DisplayName("긴 수집이 공급자 재시도 대기 중이고 루프는 틱을 이어 갈 때 stop하면")
  class StopWhileTickingDuringLongCollection {
    SourceInstanceId source;

    @BeforeEach
    void givenLongCollectionWhileLoopTicks() {
      source = scenario.givenSource("a");
      scenario.collector(WAITS_ON_RATE_LIMITED_PROVIDER).polling(FAST_POLL);
      scenario.start();
      scenario.awaitProviderCall();
    }

    @Test
    @DisplayName("그 run은 cancelled로 기록된다")
    void runIsCancelled() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.latestRun(source).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("루프가 끝난 뒤에 돌아온다")
    void returnsAfterLoopJoined() throws Exception {
      // when
      scenario.stop();

      // then
      assertThat(scenario.loopTerminated()).isTrue();
    }
  }

  @Nested
  @DisplayName("수집기가 예외를 던지면")
  class CollectorThrows {
    @BeforeEach
    void givenSource() {
      scenario.givenSource("a");
    }

    @Test
    @DisplayName("원인을 담은 그 소스의 실패 시도로 남는다")
    void recordedAsFailedAttempt() throws Exception {
      // given
      scenario.collector(ALWAYS_FAILS);

      // when
      scenario.start();
      CollectionDaemon.CycleOutcome first = scenario.nextCycle();

      // then
      assertThat(first.collection().orElseThrow().attempts())
          .singleElement()
          .extracting(Attempt::outcome)
          .isInstanceOfSatisfying(
              AttemptOutcome.Failed.class,
              f -> assertThat(f.cause()).hasMessage(COLLECTOR_FAILURE));
    }

    @Test
    @DisplayName("데몬 수준의 사이클 오류는 아니다")
    void notACycleError() throws Exception {
      // given
      scenario.collector(ALWAYS_FAILS);

      // when
      scenario.start();
      CollectionDaemon.CycleOutcome first = scenario.nextCycle();

      // then
      assertThat(first.error()).isEmpty();
    }

    @Test
    @DisplayName("다음 사이클에서 그 소스를 다시 시도한다")
    void retriesNextCycle() throws Exception {
      // given: 첫 호출만 실패한다
      scenario.collector(FAILS_FIRST_CALL).polling(FAST_POLL);

      // when
      scenario.start();
      scenario.nextCycle();
      CollectionDaemon.CycleOutcome second = scenario.nextCycle();

      // then
      assertThat(second.collection().orElseThrow().attempts())
          .singleElement()
          .extracting(Attempt::failed)
          .isEqualTo(false);
    }
  }

  @Nested
  @DisplayName("감독 사이클 자체가 실패하면")
  class CycleFails {
    @BeforeEach
    void givenFailingStorage() {
      scenario.supervisorStorage(FAILS);
    }

    @Test
    @DisplayName("사이클 결과에 수집 없이 오류만 남는다")
    void outcomeHasOnlyError() throws Exception {
      // when
      scenario.start();
      CollectionDaemon.CycleOutcome first = scenario.nextCycle();

      // then
      assertThat(first.collection()).isEmpty();
      assertThat(first.error()).hasValueSatisfying(e -> assertThat(e).hasMessage(STORAGE_FAILURE));
    }

    @Test
    @DisplayName("루프는 다음 사이클을 계속 돈다")
    void loopContinues() throws Exception {
      // given
      scenario.polling(FAST_POLL);

      // when
      CollectionDaemon daemon = scenario.start();
      scenario.awaitCycles(2);

      // then
      assertThat(daemon.cycles()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("health 상태는 사이클 오류를 예외 종류의 단순 이름으로 읽는다")
    void healthStateNamesCycleErrorByKind() throws Exception {
      // when
      scenario.start();
      scenario.nextCycle();

      // then
      assertThat(scenario.healthState().lastCycleError()).contains("IllegalStateException");
    }
  }

  @Nested
  @DisplayName("루프가 치명 오류로 끝나면")
  class LoopDies {
    CollectionDaemon daemon;

    @BeforeEach
    void givenLoopDied() throws InterruptedException {
      scenario.supervisorStorage(FAILS_FATALLY);
      daemon = scenario.start();
      scenario.awaitLoopEnded();
    }

    @Test
    @DisplayName("종료 코드는 70이다(래퍼 scos-daemon.sh의 루프 사망 계약)")
    void exitCodeIsLoopDied() {
      // when
      int exitCode = daemon.getExitCode();

      // then
      assertThat(exitCode).isEqualTo(70);
    }

    @Test
    @DisplayName("루프는 살아 있지 않다")
    void loopIsNotAlive() {
      // when
      boolean alive = daemon.loopAlive();

      // then
      assertThat(alive).isFalse();
    }

    @Test
    @DisplayName("그 치명 오류를 돌려준다")
    void exposesFatalError() {
      // when
      Optional<Throwable> fatal = daemon.fatalError();

      // then
      assertThat(fatal).hasValueSatisfying(e -> assertThat(e).isInstanceOf(LinkageError.class));
    }

    @Test
    @DisplayName("health 상태는 치명 오류를 예외 종류의 단순 이름으로 읽는다")
    void healthStateNamesFatalErrorByKind() {
      // when
      DaemonHealthIndicator.DaemonState state = scenario.healthState();

      // then
      assertThat(state.fatalError()).contains("LinkageError");
    }
  }

  @Nested
  @DisplayName("정비 간격(24시간)으로 사이클을 돌면")
  class Maintenance {
    @BeforeEach
    void givenSource() {
      scenario.givenSource("a");
    }

    @Test
    @DisplayName("첫 사이클에 한 번 돈다")
    void runsOnFirstCycle() throws Exception {
      // when
      scenario.start();
      scenario.awaitFirstCycle();

      // then
      assertThat(scenario.maintenanceRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("간격이 지나기 전에는 다시 돌지 않는다")
    void runsOnceWithinInterval() throws Exception {
      // given
      scenario.polling(FAST_POLL);

      // when
      scenario.start();
      scenario.awaitCycles(3);

      // then
      assertThat(scenario.maintenanceRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("간격이 지나면 다음 사이클에서 다시 돈다")
    void runsAgainAfterInterval() throws Exception {
      // given
      scenario.polling(FAST_POLL);
      scenario.start();
      scenario.awaitFirstCycle();

      // when
      scenario.advanceClock(MAINTENANCE_INTERVAL.plusHours(1));
      scenario.awaitCycleStartedAfterNow();

      // then
      assertThat(scenario.maintenanceRuns()).isEqualTo(2);
    }

    @Test
    @DisplayName("정비가 실패해도 간격이 지나기 전에는 다시 돌지 않는다(폭주 방지)")
    void failedMaintenanceWaitsForInterval() throws Exception {
      // given
      scenario.polling(FAST_POLL).maintenanceSweepFails();

      // when
      scenario.start();
      scenario.awaitCycles(3);

      // then
      assertThat(scenario.maintenanceRuns()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("수집이 공급자 재시도 대기 중일 때 stop하면")
  class StopDuringCollection {
    SourceInstanceId source;

    @BeforeEach
    void givenCollectionWaitingOnProvider() {
      source = scenario.givenSource("a");
      scenario.collector(WAITS_ON_RATE_LIMITED_PROVIDER);
      scenario.start();
      scenario.awaitProviderCall();
      if (!scenario.holdsActiveClaim(source))
        throw new IllegalStateException("given 준비 실패: 수집 점유가 살아 있지 않다");
    }

    @Test
    @DisplayName("그 run은 cancelled로 기록된다")
    void runIsCancelled() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.latestRun(source).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소는 run의 오류로 세지 않는다")
    void cancellationIsNotAnError() {
      // when
      scenario.stop();

      // then
      CollectionRun run = scenario.latestRun(source);
      assertThat(run.errorCount()).isZero();
    }

    @Test
    @DisplayName("수집 점유가 해제된다")
    void claimIsReleased() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.holdsActiveClaim(source)).isFalse();
    }

    @Test
    @DisplayName("루프가 끝난 뒤에 돌아온다")
    void returnsAfterLoopJoined() throws Exception {
      // when
      scenario.stop();

      // then
      assertThat(scenario.loopTerminated()).isTrue();
    }

    @Test
    @DisplayName("데몬은 running이 아니다")
    void isNotRunning() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.daemon().isRunning()).isFalse();
    }

    @Test
    @DisplayName("worker 실행기를 닫는다")
    void shutsDownWorkers() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.workersShutDown()).isTrue();
    }
  }

  @Nested
  @DisplayName("투영이 공급자 재시도 대기 중일 때 stop하면")
  class StopDuringProjection {
    SourceInstanceId slack;

    @BeforeEach
    void givenProjectionWaitingOnProvider() {
      slack = scenario.givenSlackSourceAwaitingProjection("slack-a");
      scenario.embedderWaitsOnRateLimitedProvider();
      scenario.start();
      scenario.awaitProviderCall();
    }

    @Test
    @DisplayName("루프 스레드의 대기를 깨워 루프가 끝난 뒤에 돌아온다")
    void wakesLoopThread() throws Exception {
      // when
      scenario.stop();

      // then
      assertThat(scenario.loopTerminated()).isTrue();
    }

    @Test
    @DisplayName("그 투영은 CANCELLED로 사이클 결과에 실린다")
    void projectionIsCancelled() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.outcomes())
          .singleElement()
          .extracting(CollectionDaemon.CycleOutcome::projections)
          .satisfies(
              reports ->
                  assertThat(reports)
                      .extracting(ProjectionRun.Report::source, ProjectionRun.Report::outcome)
                      .containsExactly(tuple(slack, ProjectionRun.Outcome.CANCELLED)));
    }

    @Test
    @DisplayName("중단된 투영은 요청의 시도 수를 올리지 않는다")
    void requestKeptWithoutAttempt() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.projectionRequestAttempts(slack)).isZero();
    }

    @Test
    @DisplayName("투영 점유가 해제돼 다음 투영이 그 요청을 처리한다")
    void nextProjectionSucceeds() {
      // when
      scenario.stop();

      // then
      assertThat(scenario.runDueProjectionsAgain())
          .containsExactly(ProjectionRun.Outcome.SUCCEEDED);
    }
  }

  @Nested
  @DisplayName("첫 사이클 뒤 poll 대기 중일 때")
  class Idle {
    CollectionDaemon daemon;

    @BeforeEach
    void givenIdleAfterFirstCycle() throws InterruptedException {
      scenario.givenSource("a");
      daemon = scenario.start();
      scenario.awaitFirstCycle();
    }

    @Test
    @DisplayName("stop하면 대기가 즉시 깨어나 루프가 끝난 뒤에 돌아온다")
    void wakesPollWait() throws Exception {
      // when
      scenario.stop();

      // then
      assertThat(scenario.loopTerminated()).isTrue();
    }

    @Test
    @DisplayName("stop하면 새 사이클은 시작되지 않는다")
    void startsNoNewCycle() {
      // when
      scenario.stop();

      // then
      assertThat(daemon.cycles()).isEqualTo(1);
    }

    @Test
    @DisplayName("stop하면 종료 코드는 0이다")
    void exitCodeIsZero() {
      // when
      scenario.stop();

      // then
      assertThat(daemon.getExitCode()).isZero();
    }

    @Test
    @DisplayName("stop(callback)은 정지를 마친 뒤 callback을 부른다")
    void callbackAfterStop() {
      // given
      List<Boolean> runningAtCallback = new ArrayList<>();

      // when
      daemon.stop(() -> runningAtCallback.add(daemon.isRunning()));

      // then
      assertThat(runningAtCallback).containsExactly(false);
    }

    @Test
    @DisplayName("awaitTermination은 루프가 살아 있는 동안 돌아오지 않는다")
    void awaitTerminationBlocksWhileRunning() {
      // when
      BackgroundThread waiter = scenario.waitForTerminationInBackground();

      // then
      assertThat(waiter.hasReturned()).isFalse();
    }

    @Test
    @DisplayName("awaitTermination은 stop 뒤에 돌아온다")
    void awaitTerminationReturnsAfterStop() throws Exception {
      // given
      BackgroundThread waiter = scenario.waitForTerminationInBackground();

      // when
      scenario.stop();

      // then
      assertThat(waiter.returnsWithin(WAIT_LIMIT)).isTrue();
    }
  }

  @Nested
  @DisplayName("루프가 인터럽트로 깨지지 않는 작업에 붙잡혀 있을 때")
  class StopWhileHeld {
    @BeforeEach
    void givenObserverHoldsCycle() {
      scenario.givenSource("a");
      scenario.observerHoldsCycle();
    }

    @Nested
    @DisplayName("shutdownTimeout(300ms) 안에 stop하면")
    class ShortShutdown {
      static final Duration SHORT_SHUTDOWN = Duration.ofMillis(300);

      @BeforeEach
      void givenLoopHeld() throws InterruptedException {
        scenario.shutdownTimeout(SHORT_SHUTDOWN);
        scenario.start();
        scenario.awaitFirstCycle();
      }

      @Test
      @DisplayName("shutdownTimeout까지 기다린 뒤 돌아온다")
      void waitsUpToShutdownTimeout() {
        // when
        Duration took = scenario.timedStop();

        // then
        assertThat(took).isGreaterThanOrEqualTo(SHORT_SHUTDOWN);
      }

      @Test
      @DisplayName("돌아온 뒤 데몬은 running이 아니다")
      void notRunningAfterTimedOutStop() {
        // when
        scenario.stop();

        // then
        assertThat(scenario.daemon().isRunning()).isFalse();
      }

      @Test
      @DisplayName("돌아온 뒤에도 루프는 아직 붙잡혀 있다")
      void returnsWhileLoopIsHeld() throws Exception {
        // when
        scenario.stop();

        // then
        assertThat(scenario.loopTerminated()).isFalse();
      }

      @Test
      @DisplayName("붙잡힌 작업이 풀리면 루프가 끝난다")
      void endsWhenReleased() throws Exception {
        // given
        scenario.stop();

        // when
        scenario.releaseObserver();

        // then
        assertThat(scenario.loopTerminatesWithin(WAIT_LIMIT)).isTrue();
      }
    }

    @Nested
    @DisplayName("shutdownTimeout(20s) 전에 stop을 부른 스레드가 인터럽트되면")
    class CallerInterrupted {
      /** 정지 상한보다 충분히 긴 shutdownTimeout — 그 전에 돌아오면 인터럽트 때문이다. */
      static final Duration LONG_SHUTDOWN = Duration.ofSeconds(20);

      @BeforeEach
      void givenLoopHeld() throws InterruptedException {
        scenario.shutdownTimeout(LONG_SHUTDOWN);
        scenario.start();
        scenario.awaitFirstCycle();
      }

      @Test
      @DisplayName("호출 스레드는 루프가 끝나기 전에 돌아온다")
      void interruptedCallerReturns() throws Exception {
        // when
        BackgroundThread stopper = scenario.stopAndInterruptCaller();

        // then
        assertThat(stopper.hasReturned()).isTrue();
        assertThat(scenario.loopTerminated()).as("루프는 아직 끝나지 않았다").isFalse();
      }

      @Test
      @DisplayName("호출 스레드의 인터럽트 표시는 보존된다")
      void interruptedCallerKeepsFlag() throws Exception {
        // when
        BackgroundThread stopper = scenario.stopAndInterruptCaller();

        // then
        assertThat(stopper.wasInterruptedAtEnd()).isTrue();
      }

      @Test
      @DisplayName("붙잡힌 작업이 풀리면 루프가 끝난다")
      void loopEndsAfterInterruptedStop() throws Exception {
        // given
        scenario.stopAndInterruptCaller();

        // when
        scenario.releaseObserver();

        // then
        assertThat(scenario.loopTerminatesWithin(WAIT_LIMIT)).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("데몬 스레드에서 start하면")
  class StartedFromDaemonThread {
    @BeforeEach
    void givenSource() {
      scenario.givenSource("a");
    }

    @Test
    @DisplayName("running이 된다")
    void runningAfterStart() throws Exception {
      // when
      CollectionDaemon daemon = scenario.startFromDaemonThread();

      // then
      assertThat(daemon.isRunning()).isTrue();
    }

    @Test
    @DisplayName("루프 스레드(scos-supervisor)는 non-daemon이라 JVM을 살려 둔다")
    void loopKeepsJvmAlive() throws Exception {
      // when
      scenario.startFromDaemonThread();
      scenario.awaitFirstCycle();

      // then: 관측자를 부른 스레드가 데몬의 루프 스레드다
      assertThat(scenario.loopThread())
          .extracting(Thread::getName, Thread::isDaemon)
          .containsExactly("scos-supervisor", false);
    }
  }

  @Nested
  @DisplayName("옵션에 양수가 아닌 기간을 주면")
  class Options {
    static final Duration VALID = Duration.ofSeconds(5);

    static Stream<Arguments> nonPositive() {
      return Stream.of(
          Arguments.of(
              "poll", (Runnable) () -> new CollectionDaemon.Options(Duration.ZERO, VALID, VALID)),
          Arguments.of(
              "maintenanceInterval",
              (Runnable) () -> new CollectionDaemon.Options(VALID, Duration.ZERO, VALID)),
          Arguments.of(
              "shutdownTimeout",
              (Runnable) () -> new CollectionDaemon.Options(VALID, VALID, Duration.ofMillis(-1))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPositive")
    @DisplayName("그 항목 이름을 밝혀 거부된다")
    void rejectsNonPositive(String option, Runnable create) {
      // when·then
      assertThatThrownBy(create::run)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(option + " must be positive");
    }
  }
}
