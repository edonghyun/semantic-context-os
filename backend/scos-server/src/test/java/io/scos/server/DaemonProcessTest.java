package io.scos.server;

import static io.scos.server.DaemonProcessScenario.SIGTERM_EXIT;
import static io.scos.server.DaemonProcessScenario.SOURCE;
import static io.scos.server.DaemonProcessScenario.STARTUP_FAILED_EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.RunStatus;
import io.scos.server.DaemonProcessScenario.Daemon;
import io.scos.server.DaemonProcessScenario.Http;
import io.scos.server.DaemonProcessScenario.Settings;
import io.scos.server.fault.DaemonLoopFault;
import io.scos.spi.connector.SourceInstanceId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0020 §7 완료 기준 (a)(b)(c): 실제 bootJar를 `java -jar … daemon`으로 띄워 SIGTERM 정상 종료, kill -9 뒤 새 PID
 * 재개, 두 프로세스 동시 실행, health/readiness/metrics를 실제 HTTP로 본다. 상황마다 프로세스 실행을 @BeforeAll에서 한 번 하고 기록을
 * 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(240)
@DisplayName("실제 데몬 프로세스는 SIGTERM·kill -9·동시 실행을 견딘다(ADR-0020 §7)")
class DaemonProcessTest {
  /** 상황마다 시나리오 하나 — 실행·기록만 하고 닫을 때 남은 프로세스를 정리한다. */
  abstract static class Situation {
    DaemonProcessScenario daemons;

    @AfterAll
    void close() {
      if (daemons != null) daemons.close();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("켜진 소스의 file: 자격 증명 파일이 없으면")
  class MissingCredentialFile extends Situation {
    static final String BROKEN = "slack-broken";

    Path missing;
    int exitCode;
    String log;

    @BeforeAll
    void start(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      missing = daemons.givenSourceWithMissingCredentialFile(BROKEN);
      Daemon daemon = daemons.start("a", Settings.defaults());
      exitCode = daemon.awaitExit();
      log = daemon.log();
    }

    @Test
    @DisplayName("데몬은 뜨지 않고 종료 코드 1로 끝난다")
    void exitsWithStartupFailure() {
      // then
      assertThat(exitCode).isEqualTo(STARTUP_FAILED_EXIT);
    }

    @Test
    @DisplayName("로그가 소스와 없는 파일 경로를 밝힌다")
    void logNamesSourceAndPath() {
      // then
      assertThat(log)
          .contains("source " + BROKEN + " (slack): credential file could not be read: " + missing);
    }

    @Test
    @DisplayName("로그에 다른 소스의 토큰 값이 없다")
    void logCarriesNoToken() {
      // then
      assertThat(log).doesNotContain(daemons.slackToken());
    }

    @Test
    @DisplayName("어떤 소스도 수집하지 않는다")
    void collectsNothing() {
      // then
      assertThat(daemons.latestRunStatus(SOURCE)).isEmpty();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("공급자 재시도 대기 중에 SIGTERM을 받으면")
  class SigtermDuringProviderWait extends Situation {
    /** 60s 대기 — 취소로만 깨어난다. */
    static final int RETRY_AFTER_SECONDS = 60;

    Http readiness;
    Http health;
    int exitCode;
    String log;

    @BeforeAll
    void startThenSigterm(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      daemons.slackRateLimitsFirstHistory(RETRY_AFTER_SECONDS);
      Daemon daemon = daemons.start("a", Settings.defaults());
      daemon.port();
      daemons.awaitHistoryRequested();
      if (daemons.latestRunStatus(SOURCE).orElseThrow() != RunStatus.RUNNING
          || !daemons.holdsClaim(SOURCE))
        throw new IllegalStateException("given 준비 실패: 진행 중 run과 점유가 없다");
      readiness = daemon.get("/actuator/health/readiness");
      health = daemon.get("/actuator/health");
      exitCode = daemon.sigterm();
      log = daemon.log();
    }

    @Test
    @DisplayName("첫 사이클 전 readiness는 OUT_OF_SERVICE(503)다")
    void notReadyBeforeFirstCycle() {
      // then
      assertThat(readiness)
          .extracting(Http::status, Http::healthStatus)
          .containsExactly(503, "OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("health의 daemon 구성요소는 사이클 0으로 UP이다")
    void daemonComponentIsUp() {
      // then
      assertThat(health)
          .extracting(
              h -> h.componentStatus("daemon"),
              h -> h.componentDetail("daemon", "running").asBoolean(),
              h -> h.componentDetail("daemon", "cycles").asLong())
          .containsExactly("UP", true, 0L);
    }

    @Test
    @DisplayName("health의 db 구성요소는 UP이다")
    void dbComponentIsUp() {
      // then
      assertThat(health.componentStatus("db")).isEqualTo("UP");
    }

    @Test
    @DisplayName("종료 코드는 143이다")
    void exitsWithSigtermCode() {
      // then
      assertThat(exitCode).isEqualTo(SIGTERM_EXIT);
    }

    @Test
    @DisplayName("진행 중 run은 cancelled로 마감된다")
    void runIsCancelled() {
      // then
      assertThat(daemons.latestRunStatus(SOURCE)).contains(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("점유가 해제된다")
    void claimIsReleased() {
      // then
      assertThat(daemons.holdsActiveClaim(SOURCE)).isFalse();
    }

    @Test
    @DisplayName("로그에 정상 정지가 남는다")
    void logsCleanStop() {
      // then
      assertThat(log).contains("daemon stopped cleanly");
    }

    @Test
    @DisplayName("로그는 줄마다 TS serve.log 형식 JSON(ts·level·event)이다")
    void logsTsJsonLines() {
      // then
      assertThat(DaemonProcessScenario.logLines(log))
          .isNotEmpty()
          .allSatisfy(line -> assertThat(line.propertyNames()).startsWith("ts", "level", "event"));
    }

    @Test
    @DisplayName("취소된 run은 info collection.run.cancelled 줄로 남는다")
    void logsCancelledRunEvent() {
      // then
      assertThat(DaemonProcessScenario.logLines(log))
          .filteredOn(line -> line.get("event").asString().equals("collection.run.cancelled"))
          .singleElement()
          .satisfies(line -> assertThat(line.get("level").asString()).isEqualTo("info"));
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("수집 중 kill -9 뒤 새 PID로 다시 띄우면")
  class RestartAfterKill9 extends Situation {
    /** 점유 만료가 빨리 오도록 짧은 TTL. */
    static final String SHORT_LEASE = "5s";

    List<String> runStatuses;
    long rawItems;
    double succeededRuns;
    double pageCommits;
    double cycles;
    double pendingRequests;
    double parkedRequests;
    double consecutiveFailures;
    double freshnessHours;
    int exitCode;

    @BeforeAll
    void killThenRestart(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      daemons.holdFirstHistory();
      Daemon first = daemons.start("a", Settings.defaults().leaseTtl(SHORT_LEASE));
      first.port();
      daemons.awaitHistoryHeld();
      first.kill9();
      daemons.releaseHistory();
      Daemon second = daemons.start("b", Settings.defaults().leaseTtl(SHORT_LEASE));
      second.port();
      daemons.awaitLatestRun(SOURCE, RunStatus.SUCCEEDED_WITH_REJECTIONS);
      rawItems = daemons.rawItems(SOURCE);
      runStatuses = daemons.runStatuses();
      second.awaitReady();
      succeededRuns = second.metric("scos.collection.runs", "status:succeeded_with_rejections");
      pageCommits = second.metric("scos.collection.page_commit");
      cycles = second.metric("scos.supervisor.cycle");
      daemons.awaitProjectionRequestsDone();
      pendingRequests = second.metric("scos.projection.requests", "state:pending");
      parkedRequests = second.metric("scos.projection.requests", "state:parked");
      consecutiveFailures = second.metric("scos.projection.consecutive_failures");
      freshnessHours = second.metric("scos.engine.freshness_hours");
      exitCode = second.sigterm();
    }

    @Test
    @DisplayName("점유 만료 뒤 재개해 원본 9건을 중복 없이 남긴다")
    void resumesWithoutDuplicates() {
      // then
      assertThat(rawItems).isEqualTo(9);
    }

    @Test
    @DisplayName("run 기록은 stale_run으로 마감된 run 뒤의 성공 run이다")
    void deadRunIsStale() {
      // then
      assertThat(runStatuses).containsExactly("failed/stale_run", "succeeded_with_rejections/-");
    }

    @Test
    @DisplayName("metrics에 성공 run 1이 보인다")
    void countsSucceededRun() {
      // then
      assertThat(succeededRuns).isEqualTo(1.0);
    }

    @Test
    @DisplayName("metrics에 페이지 커밋이 보인다")
    void countsPageCommits() {
      // then
      assertThat(pageCommits).isGreaterThan(0);
    }

    @Test
    @DisplayName("metrics에 사이클이 보인다")
    void countsCycles() {
      // then
      assertThat(cycles).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("투영 뒤 pending 요청 게이지는 0이다")
    void pendingGaugeIsZero() {
      // then
      assertThat(pendingRequests).isZero();
    }

    @Test
    @DisplayName("투영 뒤 parked 요청 게이지는 0이다")
    void parkedGaugeIsZero() {
      // then
      assertThat(parkedRequests).isZero();
    }

    @Test
    @DisplayName("투영 뒤 연속 실패 게이지는 0이다")
    void consecutiveFailuresGaugeIsZero() {
      // then
      assertThat(consecutiveFailures).isZero();
    }

    @Test
    @DisplayName("투영된 공간의 데이터 나이는 유한한 0 이상이다")
    void freshnessIsFinite() {
      // then
      assertThat(freshnessHours).isFinite().isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("새 데몬도 SIGTERM에 143으로 끝난다")
    void restartedDaemonStopsCleanly() {
      // then
      assertThat(exitCode).isEqualTo(SIGTERM_EXIT);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("두 데몬을 동시에 띄우면")
  class TwoDaemons extends Situation {
    int runCount;
    long rawItems;
    double collectedByFirst;
    int secondRunsMetricStatus;
    int firstExit;
    int secondExit;

    @BeforeAll
    void runBoth(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      daemons.holdFirstHistory();
      Daemon first = daemons.start("a", Settings.defaults());
      first.port();
      daemons.awaitHistoryHeld();
      Daemon second = daemons.start("b", Settings.defaults());
      second.awaitReady();
      daemons.releaseHistory();
      daemons.awaitLatestRun(SOURCE, RunStatus.SUCCEEDED_WITH_REJECTIONS);
      second.awaitCycles(2);
      runCount = daemons.runCount(SOURCE);
      rawItems = daemons.rawItems(SOURCE);
      collectedByFirst = first.metric("scos.collection.runs", "status:succeeded_with_rejections");
      secondRunsMetricStatus = second.metricStatus("scos.collection.runs");
      firstExit = first.sigterm();
      secondExit = second.sigterm();
    }

    @Test
    @DisplayName("수집 run은 하나다")
    void collectsOnce() {
      // then
      assertThat(runCount).isEqualTo(1);
    }

    @Test
    @DisplayName("원본은 9건이다(중복 0)")
    void noDuplicates() {
      // then
      assertThat(rawItems).isEqualTo(9);
    }

    @Test
    @DisplayName("먼저 점유한 데몬이 수집했다")
    void firstCollected() {
      // then
      assertThat(collectedByFirst).isEqualTo(1.0);
    }

    @Test
    @DisplayName("둘째 데몬에는 수집 지표가 없다(유예됐다)")
    void secondDeferred() {
      // then
      assertThat(secondRunsMetricStatus).isEqualTo(404);
    }

    @Test
    @DisplayName("둘 다 SIGTERM에 143으로 끝난다")
    void bothStopCleanly() {
      // then
      assertThat(List.of(firstExit, secondExit)).containsExactly(SIGTERM_EXIT, SIGTERM_EXIT);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("worker 1인 두 데몬이 소스 둘을 나눠 가지면")
  class TwoDaemonsTwoSources extends Situation {
    SourceInstanceId second;
    Optional<RunStatus> firstSourceWhileHeld;
    double collectedBySecondDaemon;
    int firstSourceRuns;
    long firstSourceItems;
    long secondSourceItems;
    List<Integer> upsertedOnSecondSource;
    double collectedByFirstDaemon;
    int firstExit;
    int secondExit;

    @BeforeAll
    void splitSources(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      second = daemons.givenSecondSource("zz-second");
      daemons.holdFirstHistory();
      Settings oneWorker = Settings.defaults().concurrency(1);
      Daemon a = daemons.start("a", oneWorker);
      a.port();
      daemons.awaitHistoryHeld();
      if (daemons.latestRunStatus(SOURCE).orElseThrow() != RunStatus.RUNNING)
        throw new IllegalStateException("given 준비 실패: A가 앞 소스(id 순)를 수집 중이 아니다");
      Daemon b = daemons.start("b", oneWorker);
      b.port();
      daemons.awaitLatestRun(second, RunStatus.SUCCEEDED_WITH_REJECTIONS);
      firstSourceWhileHeld = daemons.latestRunStatus(SOURCE);
      collectedBySecondDaemon =
          b.metric("scos.collection.runs", "status:succeeded_with_rejections");
      daemons.releaseHistory();
      daemons.awaitLatestRun(SOURCE, RunStatus.SUCCEEDED_WITH_REJECTIONS);
      firstSourceRuns = daemons.runCount(SOURCE);
      firstSourceItems = daemons.rawItems(SOURCE);
      secondSourceItems = daemons.rawItems(second);
      upsertedOnSecondSource = daemons.upsertedPerRun(second);
      collectedByFirstDaemon = a.metric("scos.collection.runs", "status:succeeded_with_rejections");
      firstExit = a.sigterm();
      secondExit = b.sigterm();
    }

    @Test
    @DisplayName("B는 A가 점유한 앞 소스를 건드리지 않는다")
    void secondLeavesHeldSource() {
      // then
      assertThat(firstSourceWhileHeld).contains(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("B는 뒤 소스 하나를 수집한다")
    void secondCollectsOtherSource() {
      // then
      assertThat(collectedBySecondDaemon).isEqualTo(1.0);
    }

    @Test
    @DisplayName("앞 소스의 run은 하나다")
    void firstSourceCollectedOnce() {
      // then
      assertThat(firstSourceRuns).isEqualTo(1);
    }

    @Test
    @DisplayName("앞 소스는 A가 수집한다")
    void firstSourceCollectedByA() {
      // then
      assertThat(collectedByFirstDaemon).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("소스마다 원본 9건이다(중복 0)")
    void eachSourceHasNineItems() {
      // then
      assertThat(List.of(firstSourceItems, secondSourceItems)).containsExactly(9L, 9L);
    }

    @Test
    @DisplayName("뒤 소스의 첫 run(B)이 9건을 넣는다")
    void firstRunOnSecondSourceInserts() {
      // then
      assertThat(upsertedOnSecondSource.get(0)).isEqualTo(9);
    }

    @Test
    @DisplayName("뒤 소스에 이후 run이 있으면 새로 넣는 것이 없다")
    void laterRunsInsertNothing() {
      // then: 이후 run이 생기는지는 타이밍에 달렸다 — 계약은 "있다면 0건"이다(tr-ops H8)
      assertThat(upsertedOnSecondSource.subList(1, upsertedOnSecondSource.size()))
          .allMatch(n -> n == 0);
    }

    @Test
    @DisplayName("둘 다 SIGTERM에 143으로 끝난다")
    void bothStopCleanly() {
      // then
      assertThat(List.of(firstExit, secondExit)).containsExactly(SIGTERM_EXIT, SIGTERM_EXIT);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("감독 루프가 치명 오류(Error)로 죽으면")
  class FatalLoopError extends Situation {
    int exitCode;
    String log;

    @BeforeAll
    void startWithFault(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      Daemon daemon = daemons.start("fault", Settings.defaults().fatalLoopFault());
      exitCode = daemon.awaitExit();
      log = daemon.log();
    }

    @Test
    @DisplayName("프로세스는 종료 코드 70으로 끝난다")
    void exitsWithLoopDied() {
      // then
      assertThat(exitCode).isEqualTo(70);
    }

    @Test
    @DisplayName("로그에 원인이 남는다")
    void logsCause() {
      // then
      assertThat(log).contains(DaemonLoopFault.MESSAGE);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("DB 풀이 worker 수에 비해 작으면(concurrency 10, 기본 풀 6)")
  class UndersizedPool extends Situation {
    static final int TOO_MANY_WORKERS = 10;

    int exitCode;
    String log;

    @BeforeAll
    void startWithTooManyWorkers(@TempDir Path dir) throws Exception {
      daemons = new DaemonProcessScenario(dir);
      Daemon daemon = daemons.start("pool", Settings.defaults().concurrency(TOO_MANY_WORKERS));
      exitCode = daemon.awaitExit();
      log = daemon.log();
    }

    @Test
    @DisplayName("종료 코드 1(기동 실패)로 끝난다")
    void refusesToStart() {
      // then
      assertThat(exitCode).isEqualTo(STARTUP_FAILED_EXIT);
    }

    @Test
    @DisplayName("로그가 풀 크기 설정을 가리킨다")
    void logsPoolSetting() {
      // then
      assertThat(log).contains("maximum-pool-size");
    }
  }
}
