package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * P-2: 커밋 직후 kill -9 → 재기동. 올바른 조립(같은 DataSource)은 복구, 잘못된 조립(Outbox 분리)은 누락. 투영 중 kill → 중복 0.
 * 상황마다 자식 JVM 실행(죽이기 + 재기동)을 @BeforeAll에서 한 번 하고 기록을 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@DisplayName("투영 요청은 원본과 한 트랜잭션에 커밋돼 kill 뒤에도 남는다(P-2)")
class PostgresOutboxKillTest {
  static final Duration MARKER_PROCESS_LIMIT = Duration.ofSeconds(60);
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(60);
  static final int ITEMS = 6;

  /** 자식 JVM 실행·마커 대기·DB 행 수. */
  abstract static class KillAndResume {
    final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
    Path dir;
    int rawItemsAtKill;
    int requestsAtKill;
    int probeUnitsAtKill;
    ChildJvm.ProbeResult resumed;

    void clean(Path dir) {
      this.dir = dir;
      PostgresSupport.truncateAll();
      jdbc.sql("DROP TABLE IF EXISTS probe_units").update();
    }

    Process probe(String name, String assembly, String phase) throws Exception {
      return ChildJvm.start(
          OutboxKillProbe.class,
          dir.resolve(name + ".log"),
          PostgresSupport.jdbcUrl(),
          PostgresSupport.username(),
          PostgresSupport.password(),
          dir.resolve("markers").toString(),
          assembly,
          phase);
    }

    void kill(Process run) throws InterruptedException {
      run.destroyForcibly();
      if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: kill -9 뒤 끝나지 않았다");
    }

    void recordStateAtKill() {
      rawItemsAtKill = count("raw_items");
      requestsAtKill = count("projection_requests");
      probeUnitsAtKill =
          jdbc.sql("SELECT to_regclass('probe_units') IS NOT NULL").query(Boolean.class).single()
              ? count("probe_units")
              : 0;
    }

    /** 재기동 자식의 종료 코드·결과 줄을 전제로 확인한다(TESTING §7) — 자식이 죽어도 "0건"으로 통과하지 않게. */
    void resume(String assembly) throws Exception {
      Process resume = probe("resume", assembly, "resume");
      if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: 재기동 프로세스가 끝나지 않았다");
      if (resume.exitValue() != 0)
        throw new IllegalStateException(
            "given 준비 실패: 재기동 종료 코드 "
                + resume.exitValue()
                + "\n"
                + ChildJvm.readLog(dir.resolve("resume.log")));
      resumed = ChildJvm.readResult(dir.resolve("resume.log"));
      if (!resumed.phase().equals("resume"))
        throw new IllegalStateException("given 준비 실패: 결과 줄이 resume이 아니다: " + resumed.phase());
    }

    int count(String table) {
      return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("올바른 조립에서 커밋 직후 kill -9하면")
  class CorrectAssembly extends KillAndResume {
    @BeforeAll
    void killAfterCommitThenResume(@TempDir Path dir) throws Exception {
      clean(dir);
      Process run = probe("run", "correct", "run");
      try {
        ChildJvm.awaitMarker(dir.resolve("markers/committed"), MARKER_PROCESS_LIMIT.toMillis());
        kill(run);
      } finally {
        if (run.isAlive()) run.destroyForcibly();
      }
      recordStateAtKill();
      resume("correct");
    }

    @Test
    @DisplayName("원본과 함께 요청 행이 커밋돼 있다")
    void requestCommittedWithItems() {
      // then
      assertThat(rawItemsAtKill).isEqualTo(ITEMS);
      assertThat(requestsAtKill).isEqualTo(1);
    }

    @Test
    @DisplayName("재기동은 남은 요청으로 전량을 한 번에 투영한다")
    void resumeProjects() {
      // then
      assertThat(resumed.getInt("projected")).isEqualTo(1);
      assertThat(resumed.getInt("units")).isEqualTo(ITEMS);
      assertThat(count("probe_units")).isEqualTo(ITEMS);
    }

    @Test
    @DisplayName("투영한 뒤 요청을 지운다")
    void resumeClearsRequest() {
      // then
      assertThat(resumed.getInt("pending")).isZero();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("Outbox가 별도 DataSource·별도 커밋인 잘못된 조립에서 커밋 직후 kill -9하면")
  class WrongAssembly extends KillAndResume {
    @BeforeAll
    void killBeforeOutboxThenResume(@TempDir Path dir) throws Exception {
      clean(dir);
      Process run = probe("run", "wrong", "run");
      try {
        ChildJvm.awaitMarker(dir.resolve("markers/committed"), MARKER_PROCESS_LIMIT.toMillis());
        kill(run); // proceed-outbox를 주기 전에 죽인다 — 원본은 커밋됐고 Outbox는 아직
      } finally {
        if (run.isAlive()) run.destroyForcibly();
      }
      recordStateAtKill();
      resume("wrong");
    }

    @Test
    @DisplayName("요청 행 없이 원본만 커밋돼 있다")
    void requestIsLost() {
      // then
      assertThat(rawItemsAtKill).isEqualTo(ITEMS);
      assertThat(requestsAtKill).isZero();
    }

    @Test
    @DisplayName("원본은 모두 남아 있다")
    void itemsSurvive() {
      // then
      assertThat(resumed.getInt("items")).isEqualTo(ITEMS);
    }

    @Test
    @DisplayName("재기동은 투영할 근거(요청)가 없어 투영이 누락된다")
    void projectionIsMissed() {
      // then
      assertThat(resumed.getInt("pending")).isZero();
      assertThat(resumed.getInt("projected")).isZero();
      assertThat(resumed.getInt("units")).isZero();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("투영 도중 kill -9 뒤 재기동하면")
  class KillMidProjection extends KillAndResume {
    @BeforeAll
    void killMidProjectionThenResume(@TempDir Path dir) throws Exception {
      clean(dir);
      Process run = probe("run", "correct", "run-kill-mid");
      try {
        ChildJvm.awaitMarker(dir.resolve("markers/committed"), MARKER_PROCESS_LIMIT.toMillis());
        ChildJvm.touch(dir.resolve("markers/proceed"));
        ChildJvm.awaitMarker(dir.resolve("markers/mid"), MARKER_PROCESS_LIMIT.toMillis());
        kill(run);
      } finally {
        if (run.isAlive()) run.destroyForcibly();
      }
      recordStateAtKill();
      resume("correct");
    }

    @Test
    @DisplayName("죽기 전에는 절반만 투영돼 있다")
    void halfProjectedAtKill() {
      // then
      assertThat(probeUnitsAtKill).isEqualTo(ITEMS / 2);
    }

    @Test
    @DisplayName("죽기 전 요청 행은 남아 있다")
    void requestPendingAtKill() {
      // then
      assertThat(requestsAtKill).isEqualTo(1);
    }

    // 중복 여부는 여기서 볼 수 없다: 프로브 투영기(probe_units PK + ON CONFLICT DO NOTHING)가 중복을 원천 차단한다.
    // 투영 멱등성은 투영 쪽 테스트의 몫이다.
    @Test
    @DisplayName("재기동은 남은 요청으로 전량을 다시 투영한다")
    void restartReprojectsFromRemainingRequest() {
      // then
      assertThat(resumed.getInt("projected")).isEqualTo(1);
      assertThat(resumed.getInt("units")).isEqualTo(ITEMS);
    }

    @Test
    @DisplayName("요청 행을 지운다")
    void clearsRequest() {
      // then
      assertThat(resumed.getInt("pending")).isZero();
      assertThat(count("projection_requests")).isZero();
    }
  }
}
