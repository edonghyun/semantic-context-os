package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.postgres.PostgresCollectionObservations;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.notion.NotionAssembly;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Notion roots 수집 중간 kill 뒤 재개(TESTING §3.6 6층): ds-tasks 행 둘째 페이지 요청에서 kill -9 → 점유 만료 뒤 새 프로세스가
 * v3 체크포인트에서 재개한다. 죽기 전 체크포인트는 TS 중단 지점(expected-resume firstRun)과 같고, 재개 뒤 active 원본은 TS 재개 결과와
 * 같다(재개 run은 정리하지 않는다). 두 프로세스 실행은 @BeforeAll에서 한 번(규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180)
@TestInstance(PER_CLASS)
@DisplayName("Notion 수집을 행 둘째 페이지에서 kill -9하고 새 프로세스로 재개하면")
class NotionCollectionKillTest {
  static final JsonNode TS_RESUME = NotionFixtures.read("expected-resume.json");
  static final long MARKER_WAIT_MS = 60_000;

  /** kill -9 뒤 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);

  /** 재개 자식 프로세스가 끝나기를 기다리는 상한. */
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  JsonNode checkpointAtKill;
  String runAtKill;
  int resumeExit;
  ChildJvm.ProbeResult resumed;
  List<String> activeIds;
  String deadRun;

  @BeforeAll
  void killThenResume(@TempDir Path dir) throws Exception {
    PostgresSupport.truncateAll();
    Process run = probe(dir, "run");
    try {
      ChildJvm.awaitMarker(dir.resolve("markers/held"), MARKER_WAIT_MS);
      run.destroyForcibly();
      if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: kill -9 뒤 끝나지 않았다");
    } finally {
      if (run.isAlive()) run.destroyForcibly();
    }
    var observe =
        new PostgresCollectionObservations(
            PostgresSupport.dataSource(), NotionAssembly.SOURCE, Clock.systemUTC());
    runAtKill = observe.runStatus(new RunId("run-run-1"));
    var stored = observe.checkpoint().orElseThrow();
    checkpointAtKill =
        NotionFixtures.JSON
            .createObjectNode()
            .put("schemaVersion", stored.schemaVersion())
            .set("value", NotionFixtures.JSON.readTree(stored.valueJson()));
    Process resume = probe(dir, "resume");
    if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 재개 프로세스가 끝나지 않았다");
    resumeExit = resume.exitValue();
    resumed = ChildJvm.readResult(dir.resolve("resume.log"));
    activeIds = List.copyOf(observe.activeVersions().keySet());
    deadRun = observe.runStatus(new RunId("run-run-1"));
  }

  Process probe(Path dir, String phase) throws Exception {
    return ChildJvm.start(
        NotionCollectionKillProbe.class,
        dir.resolve(phase + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        NotionFixtures.DIR.toAbsolutePath().toString(),
        phase);
  }

  static List<String> tsActiveIds() {
    List<String> out = new ArrayList<>();
    TS_RESUME.get("secondRun").get("activeExternalIds").forEach(id -> out.add(id.asString()));
    return out;
  }

  @Test
  @DisplayName("죽기 전 체크포인트는 TS 중단 지점과 같다(그 페이지는 커밋되지 않았다)")
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
  @DisplayName("재개한 run은 TS 둘째 run처럼 succeeded다")
  void resumedRunStatusMatchesTs() {
    // then
    assertThat(resumed.get("status"))
        .isEqualTo(TS_RESUME.get("secondRun").get("report").get("status").asString());
  }

  @Test
  @DisplayName("active 원본은 TS 재개 결과와 같다")
  void activeItemsMatchTs() {
    // then
    assertThat(activeIds).containsExactlyInAnyOrderElementsOf(tsActiveIds());
  }

  @Test
  @DisplayName("죽은 run은 재개 때 failed(stale_run)로 마감된다")
  void deadRunClosedAsStale() {
    // then
    assertThat(deadRun).isEqualTo("failed/stale_run");
  }
}
