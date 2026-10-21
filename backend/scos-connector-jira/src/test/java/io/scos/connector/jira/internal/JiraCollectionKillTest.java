package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.postgres.PostgresCollectionObservations;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.jira.JiraAssembly;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Jira 페이지 중간 kill 뒤 재개(TESTING §3.6 6층): 이슈 40004·40005 배치 안에서 kill -9 → 점유 만료 뒤 새 프로세스가 체크포인트에서
 * 재개한다. 죽기 전 커밋은 TS 중단 지점(expected-resume firstRun)과 같고, 재개 뒤 원본은 한 번에 수집한 것과 같다. 두 프로세스
 * 실행은 @BeforeAll에서 한 번(규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180)
@TestInstance(PER_CLASS)
@DisplayName("Jira 수집을 이슈 40005 배치에서 kill -9하고 새 프로세스로 재개하면")
class JiraCollectionKillTest {
  /** kill 프로브의 첫 run(프로브가 매기는 run id). */
  static final RunId DEAD_RUN = new RunId("run-run-1");

  static final JsonNode TS_RESUME = JiraFixtures.read("expected-resume.json");
  static final long MARKER_WAIT_MS = 60_000;

  /** kill -9 뒤 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);

  /** 재개 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  JsonNode checkpointAtKill;
  int resumeExit;
  ChildJvm.ProbeResult resumed;
  Map<String, String> versions;
  String deadRun;
  String deadRunAtKill;

  @BeforeAll
  void killThenResume(@TempDir Path dir) throws Exception {
    PostgresSupport.truncateAll();
    Process run = probe(dir, "run");
    try {
      ChildJvm.awaitMarker(dir.resolve("markers/gated"), MARKER_WAIT_MS);
      run.destroyForcibly();
      if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: kill -9 뒤 끝나지 않았다");
    } finally {
      if (run.isAlive()) run.destroyForcibly();
    }
    var observe =
        new PostgresCollectionObservations(
            PostgresSupport.dataSource(), JiraAssembly.SOURCE, Clock.systemUTC());
    checkpointAtKill = JiraTsRuns.checkpointJson(observe.checkpoint().orElseThrow());
    deadRunAtKill = observe.runStatus(DEAD_RUN);
    Process resume = probe(dir, "resume");
    if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 재개 프로세스가 끝나지 않았다");
    resumeExit = resume.exitValue();
    resumed = ChildJvm.readResult(dir.resolve("resume.log"));
    versions = observe.activeVersions();
    deadRun = observe.runStatus(DEAD_RUN);
  }

  Process probe(Path dir, String phase) throws Exception {
    return ChildJvm.start(
        JiraCollectionKillProbe.class,
        dir.resolve(phase + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        JiraFixtures.DIR.toAbsolutePath().toString(),
        phase);
  }

  @Test
  @DisplayName("죽기 전 run은 running이다")
  void runIsRunningAtKill() {
    // then
    assertThat(deadRunAtKill).isEqualTo("running/-");
  }

  @Test
  @DisplayName("죽기 전 체크포인트는 TS 중단 지점과 같다(그 배치는 커밋되지 않았다)")
  void checkpointAtKillMatchesTs() {
    // then
    assertThat(checkpointAtKill).isEqualTo(JiraTsRuns.crashedRunCheckpoint());
  }

  @Test
  @DisplayName("재개 프로세스는 종료 코드 0으로 끝난다")
  void resumeExitsCleanly() {
    // then
    assertThat(resumeExit).isZero();
  }

  @Test
  @DisplayName("재개한 run은 TS 둘째 run처럼 succeeded_with_rejections다")
  void resumedRunStatusMatchesTs() {
    // then
    assertThat(resumed.get("status"))
        .isEqualTo(TS_RESUME.get("secondRun").get("report").get("status").asString());
  }

  @Test
  @DisplayName("원본은 한 번에 수집한 것과 같다")
  void itemsMatchSingleCollection() {
    // then
    assertThat(versions).isEqualTo(JiraRuntimeScenario.versionsOf(TS_RESUME.get("items")));
  }

  @Test
  @DisplayName("죽은 run은 재개 때 failed(stale_run)로 마감된다")
  void deadRunClosedAsStale() {
    // then
    assertThat(deadRun).isEqualTo("failed/stale_run");
  }
}
