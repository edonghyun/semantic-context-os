package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.postgres.PostgresCollectionObservations;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.googledrive.GoogleDriveAssembly;
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
 * Drive 시트 범위 중간 kill 뒤 재개(TESTING §3.6 6층): sheet-1의 셋째 범위 요청에서 kill -9 → 점유 만료 뒤 새 프로세스가 체크포인트(범위
 * 작업과 그 파일 메타데이터가 실린 큐)에서 재개한다. 죽기 전 커밋은 TS 중단 지점(expected-resume firstRun)과 같고, 재개 뒤 원본은 TS 재개 결과와
 * 같다. 두 프로세스 실행은 @BeforeAll에서 한 번(규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180)
@TestInstance(PER_CLASS)
@DisplayName("Drive 수집을 시트 범위 요청에서 kill -9하고 새 프로세스로 재개하면")
class GoogleDriveCollectionKillTest {
  static final JsonNode TS_RESUME = GoogleDriveFixtures.read("expected-resume.json");
  static final long MARKER_WAIT_MS = 60_000;

  /** kill -9 뒤 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);

  /** 재개 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  JsonNode checkpointAtKill;
  String runAtKill;
  int resumeExit;
  ChildJvm.ProbeResult resumed;
  Map<String, String> versions;
  String deadRun;

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
            PostgresSupport.dataSource(), GoogleDriveAssembly.SOURCE, Clock.systemUTC());
    checkpointAtKill = GoogleDriveViews.checkpointJson(observe.checkpoint().orElseThrow());
    runAtKill = observe.runStatus(new RunId("run-run-1"));
    Process resume = probe(dir, "resume");
    if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 재개 프로세스가 끝나지 않았다");
    resumeExit = resume.exitValue();
    resumed = ChildJvm.readResult(dir.resolve("resume.log"));
    versions = observe.activeVersions();
    deadRun = observe.runStatus(new RunId("run-run-1"));
  }

  Process probe(Path dir, String phase) throws Exception {
    return ChildJvm.start(
        GoogleDriveCollectionKillProbe.class,
        dir.resolve(phase + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        GoogleDriveFixtures.DIR.toAbsolutePath().toString(),
        phase);
  }

  @Test
  @DisplayName("죽기 전 체크포인트는 TS 중단 지점과 같다(셋째 범위는 커밋되지 않았다)")
  void checkpointAtKillMatchesTs() {
    // then
    assertThat(checkpointAtKill)
        .isEqualTo(TS_RESUME.get("firstRun").get("checkpoint").get("checkpoint"));
  }

  @Test
  @DisplayName("죽기 전 run은 running으로 남아 있다")
  void runIsRunningAtKill() {
    // then
    assertThat(runAtKill).isEqualTo("running/-");
  }

  @Test
  @DisplayName("재개 프로세스는 종료 코드 0으로 끝난다")
  void resumeExitsCleanly() {
    // then
    assertThat(resumeExit).isZero();
  }

  @Test
  @DisplayName("재개한 run은 TS 재개 run과 같은 상태로 끝난다")
  void resumedRunStatusMatchesTs() {
    // then
    assertThat(resumed.get("status"))
        .isEqualTo(TS_RESUME.get("secondRun").get("report").get("status").asString());
  }

  @Test
  @DisplayName("재개한 run은 TS 재개 run과 같은 페이지 수를 커밋한다")
  void resumedRunPagesMatchTs() {
    // then
    assertThat(resumed.getInt("pages"))
        .isEqualTo(TS_RESUME.get("secondRun").get("report").get("pagesApplied").asInt());
  }

  @Test
  @DisplayName("원본은 TS 재개 결과와 같다")
  void itemsMatchTs() {
    // then
    assertThat(versions)
        .isEqualTo(GoogleDriveCollectionContract.TsRuntimeRun.versions(TS_RESUME.get("items")));
  }

  @Test
  @DisplayName("죽은 run은 재개 때 failed(stale_run)로 마감된다")
  void deadRunClosedAsStale() {
    // then
    assertThat(deadRun).isEqualTo("failed/stale_run");
  }
}
