package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 완료 기준 "투영 실패 후 재처리·중복 0"의 kill 변형: 투영 도중 kill -9 → 재기동이 요청 행을 보고 재투영 → 유닛 집합 = TS 기대, 중복 0, 요청 행
 * 0. 두 프로세스 실행을 @BeforeAll에서 한 번 하고 기록을 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180)
@TestInstance(PER_CLASS)
@DisplayName("투영 도중(첫 문서 커밋 뒤) kill -9 뒤 재기동하면")
class ProjectionKillTest {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final SpaceId SPACE = new SpaceId("legacy");
  static final Duration COLLECT_PROCESS_LIMIT = Duration.ofSeconds(90);
  static final Duration MARKER_PROCESS_LIMIT = Duration.ofSeconds(60);
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  int unitsAtKill;
  int pendingAtKill;
  int resumeExit;
  ChildJvm.ProbeResult resumed;
  List<String> unitIds;

  @BeforeAll
  void killThenResume(@TempDir Path dir) throws Exception {
    PostgresSupport.truncateAll();
    Process run = probe(dir, "run", "run");
    try {
      ChildJvm.awaitMarker(dir.resolve("markers/collected"), COLLECT_PROCESS_LIMIT.toMillis());
      ChildJvm.awaitMarker(dir.resolve("markers/mid"), MARKER_PROCESS_LIMIT.toMillis());
      run.destroyForcibly();
      if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: kill -9 뒤 프로세스가 끝나야 한다");
    } finally {
      if (run.isAlive()) run.destroyForcibly();
    }
    var units = PostgresTestAdapters.unitStore(PostgresSupport.dataSource());
    unitsAtKill = units.listAll(SPACE).size();
    pendingAtKill =
        PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource())
            .outbox()
            .pending()
            .size();
    Process resume = probe(dir, "resume", "resume");
    if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 재개 프로세스가 끝나야 한다");
    resumeExit = resume.exitValue();
    resumed = ChildJvm.readResult(dir.resolve("resume.log"));
    unitIds = units.listAll(SPACE).stream().map(ContextUnit::id).toList();
  }

  Process probe(Path dir, String name, String phase) throws Exception {
    return ChildJvm.start(
        ProjectionKillProbe.class,
        dir.resolve(name + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        FIXTURES.resolve("slack-dataset.json").toString(),
        phase);
  }

  @Test
  @DisplayName("죽기 전에는 첫 문서만 커밋돼 있다")
  void stateAtKill() {
    // then
    assertThat(unitsAtKill).isEqualTo(1);
  }

  @Test
  @DisplayName("죽기 전 요청 행은 남아 있다")
  void requestPendingAtKill() {
    // then
    assertThat(pendingAtKill).isEqualTo(1);
  }

  @Test
  @DisplayName("재기동 프로세스는 종료 코드 0으로 끝난다")
  void resumeSucceeds() {
    // then
    assertThat(resumeExit).isZero();
  }

  @Test
  @DisplayName("재투영은 succeeded다")
  void reprojectionSucceeds() {
    // then
    assertThat(resumed.get("outcome")).isEqualTo("succeeded");
  }

  @Test
  @DisplayName("유닛은 중복 없이 3건이다")
  void noDuplicates() {
    // then
    assertThat(resumed.getInt("units")).isEqualTo(3);
    assertThat(resumed.getInt("distinct")).isEqualTo(3);
  }

  @Test
  @DisplayName("유닛 집합이 TS 기대와 같다")
  void unitsMatchTs() {
    // then
    assertThat(unitIds).containsExactlyInAnyOrderElementsOf(ProjectionScenario.expectedUnitIds());
  }

  @Test
  @DisplayName("요청 행은 0이다")
  void requestCompleted() {
    // then
    assertThat(resumed.getInt("pending")).isZero();
  }
}
