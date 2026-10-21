package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.RunStatus;
import io.scos.connector.slack.SlackAssembly;
import io.scos.support.ChildJvm;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 완료 기준 "중단(kill) 후 재개": 1페이지 커밋 뒤 kill -9 → 점유 만료 뒤 새 프로세스가 체크포인트에서 재개, 원본 9건 중복 0, 죽은 run은
 * failed(stale_run). 두 프로세스 실행을 @BeforeAll에서 한 번 하고 기록을 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180) // hang 방지 상한 — 동기화는 마커 파일 대기로 한다
@DisplayName("kill -9 뒤 재개는 체크포인트에서 이어 받아 중복이 없다(완료 기준)")
class CollectionKillTest {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final Duration MARKER_PROCESS_LIMIT = Duration.ofSeconds(60);
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  Process probe(Path dir, String name, String phase, String owner) throws Exception {
    return ChildJvm.start(
        CollectionKillProbe.class,
        dir.resolve(name + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        FIXTURES.resolve("slack-dataset.json").toString(),
        phase,
        owner);
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("수집 프로세스를 2페이지 커밋 뒤 kill -9하고 새 프로세스로 재개하면")
  class KillAfterTwoPages {
    long revisionAtKill;
    RunStatus deadRunAtKill;
    int resumeExit;
    ChildJvm.ProbeResult resumed;
    long rawItems;
    RunStatus latest;

    @BeforeAll
    void killThenResume(@TempDir Path dir) throws Exception {
      PostgresSupport.truncateAll();
      Process run = probe(dir, "run", "run", "run");
      try {
        ChildJvm.awaitMarker(dir.resolve("markers/gated"), MARKER_PROCESS_LIMIT.toMillis());
        run.destroyForcibly();
        if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
          throw new IllegalStateException("given 준비 실패: kill -9 뒤 끝나지 않았다");
      } finally {
        if (run.isAlive()) run.destroyForcibly();
      }
      var observe =
          new PostgresCollectionObservations(
              PostgresSupport.dataSource(), SlackAssembly.SOURCE, Clock.systemUTC());
      revisionAtKill = observe.checkpointRevision();
      deadRunAtKill = observe.run(CollectionKillProbe.DEAD_RUN).status();
      Process resume = probe(dir, "resume", "resume", "resume");
      if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: 재개 프로세스가 끝나지 않았다");
      resumeExit = resume.exitValue();
      resumed = ChildJvm.readResult(dir.resolve("resume.log"));
      if (!resumed.phase().equals("resume"))
        throw new IllegalStateException("given 준비 실패: 재개 프로세스의 결과 줄이 아니다: " + resumed.phase());
      rawItems = observe.rawItems();
      latest =
          observe
              .run(
                  observe
                      .latestRunId()
                      .orElseThrow(() -> new IllegalStateException("given 준비 실패: 최신 run이 없다")))
              .status();
    }

    @Test
    @DisplayName("죽기 전에는 2페이지(history 1 + replies 1)가 커밋돼 있다")
    void stateAtKill() {
      // then
      assertThat(revisionAtKill).isEqualTo(2);
    }

    @Test
    @DisplayName("죽기 전 run은 running이다")
    void runIsRunningAtKill() {
      // then
      assertThat(deadRunAtKill).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("재개 프로세스는 종료 코드 0으로 끝난다")
    void resumeEndsCleanly() {
      // then
      assertThat(resumeExit).isZero();
    }

    @Test
    @DisplayName("재개 run은 succeeded_with_rejections로 끝난다")
    void resumedRunSucceeds() {
      // then
      assertThat(resumed.get("status")).isEqualTo("succeeded_with_rejections");
    }

    @Test
    @DisplayName("재개 run은 남은 3페이지만 수집한다")
    void collectsOnlyRemainingPages() {
      // then
      assertThat(resumed.getInt("pages")).isEqualTo(3);
    }

    @Test
    @DisplayName("재개 뒤 체크포인트 revision은 5다")
    void reachesFinalRevision() {
      // then
      assertThat(resumed.getInt("revision")).isEqualTo(5);
    }

    @Test
    @DisplayName("원본은 9건이다(중복 0)")
    void noDuplicates() {
      // then
      assertThat(resumed.getInt("count")).isEqualTo(9);
      assertThat(rawItems).isEqualTo(9);
    }

    @Test
    @DisplayName("죽은 run은 failed(stale_run)로 마감된다")
    void deadRunIsStale() {
      // then
      assertThat(resumed.get("deadRun")).isEqualTo("failed/stale_run");
    }

    @Test
    @DisplayName("최신 run은 재개 run이다")
    void latestRunIsResumed() {
      // then
      assertThat(latest).isEqualTo(RunStatus.SUCCEEDED_WITH_REJECTIONS);
    }

    @Test
    @DisplayName("투영 요청 하나가 남는다")
    void leavesOneProjectionRequest() {
      // then
      assertThat(resumed.getInt("pending")).isEqualTo(1);
    }
  }
}
