package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.RunStatus;
import io.scos.connector.tiro.TiroAssembly;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tiro 수집을 첫 목록 페이지 커밋 뒤 kill -9하고 새 프로세스로 다시 수집한다. Tiro는 커서로 재개하지 않고 처음부터 다시 걷는다 — 그래야 완료 정리가 앞
 * 페이지의 노트를 지우지 않는다. 두 프로세스 실행을 @BeforeAll에서 한 번 하고 기록을 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@Timeout(180) // hang 방지 상한 — 동기화는 마커 파일 대기로 한다
@DisplayName("Tiro는 kill 뒤 처음부터 다시 걸어 앞 페이지의 노트를 지킨다")
class TiroCollectionKillTest {
  static final Duration MARKER_PROCESS_LIMIT = Duration.ofSeconds(60);
  static final Duration KILL_PROCESS_LIMIT = Duration.ofSeconds(10);
  static final Duration RESUME_PROCESS_LIMIT = Duration.ofSeconds(90);

  /** 데이터셋의 목록 페이지 수, 읽을 수 있는 노트 수, 읽을 수 없는 노트 수(n-locked 상세 403·n-gone 상세 404 → tombstone). */
  static final int LIST_PAGES = 2;

  static final int READABLE_NOTES = 4;
  static final int UNREADABLE_NOTES = 2;

  Process probe(Path dir, String name, String phase, String owner) throws Exception {
    return ChildJvm.start(
        TiroCollectionKillProbe.class,
        dir.resolve(name + ".log"),
        PostgresSupport.jdbcUrl(),
        PostgresSupport.username(),
        PostgresSupport.password(),
        dir.resolve("markers").toString(),
        TiroAssembly.DATASET.toString(),
        phase,
        owner);
  }

  /** 수집 프로세스를 띄워 둘째 목록 페이지 요청에서 멈추면 kill -9한다(첫 목록 페이지는 커밋됨). */
  void killAtSecondListPage(Path dir) throws Exception {
    Process run = probe(dir, "run", "run", "run");
    try {
      ChildJvm.awaitMarker(dir.resolve("markers/gated"), MARKER_PROCESS_LIMIT.toMillis());
      run.destroyForcibly();
      if (!run.waitFor(KILL_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("given 준비 실패: kill -9 뒤 끝나지 않았다");
    } finally {
      if (run.isAlive()) run.destroyForcibly();
    }
  }

  /** 점유 만료를 기다려 새 프로세스로 다시 수집한다. */
  Process recollect(Path dir) throws Exception {
    Process resume = probe(dir, "resume", "resume", "resume");
    if (!resume.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("given 준비 실패: 재수집 프로세스가 끝나지 않았다");
    return resume;
  }

  ChildJvm.ProbeResult resultOf(Path dir, String phase) throws Exception {
    ChildJvm.ProbeResult result = ChildJvm.readResult(dir.resolve(phase + ".log"));
    if (!result.phase().equals(phase))
      throw new IllegalStateException(
          "given 준비 실패: " + phase + " 프로세스의 결과 줄이 아니다: " + result.phase());
    return result;
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("Tiro 수집을 첫 목록 페이지 커밋 뒤 kill -9하고 다시 수집하면")
  class KillAfterFirstPage {
    long revisionAtKill;
    RunStatus deadRunAtKill;
    int resumeExit;
    ChildJvm.ProbeResult resumed;

    @BeforeAll
    void killThenRecollect(@TempDir Path dir) throws Exception {
      PostgresSupport.truncateAll();
      killAtSecondListPage(dir);
      var adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
      revisionAtKill = adapters.checkpoints().read(TiroAssembly.SOURCE).revision();
      deadRunAtKill =
          adapters
              .runs()
              .get(TiroCollectionKillProbe.DEAD_RUN)
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: 죽은 run 기록이 없다"))
              .status();
      Process resume = recollect(dir);
      resumeExit = resume.exitValue();
      resumed = resultOf(dir, "resume");
    }

    @Test
    @DisplayName("죽기 전에는 첫 목록 페이지가 커밋돼 있다")
    void firstPageCommittedAtKill() {
      // then
      assertThat(revisionAtKill).isOne();
    }

    @Test
    @DisplayName("죽기 전 run은 running이다")
    void runIsRunningAtKill() {
      // then
      assertThat(deadRunAtKill).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("재수집 프로세스는 종료 코드 0으로 끝난다")
    void recollectEndsCleanly() {
      // then
      assertThat(resumeExit).isZero();
    }

    @Test
    @DisplayName("재수집 run은 succeeded로 끝난다")
    void recollectSucceeds() {
      // then
      assertThat(resumed.get("status")).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("재수집은 목록 첫 페이지부터 모든 페이지를 다시 걷는다")
    void recollectsEveryPage() {
      // then
      assertThat(resumed.getInt("pages")).isEqualTo(LIST_PAGES);
    }

    @Test
    @DisplayName("첫 페이지의 노트도 정리되지 않는다")
    void keepsFirstPageNotes() {
      // then
      assertThat(resumed.getInt("count")).isEqualTo(READABLE_NOTES);
    }

    @Test
    @DisplayName("상세가 403·404인 노트만 tombstone이다")
    void onlyUnreadableNotesAreTombstoned() {
      // then
      assertThat(resumed.getInt("tombstones")).isEqualTo(UNREADABLE_NOTES);
    }

    @Test
    @DisplayName("죽은 run은 failed(stale_run)로 마감된다")
    void deadRunIsStale() {
      // then
      assertThat(resumed.get("deadRun")).isEqualTo("failed/stale_run");
    }
  }

  /**
   * 위 상황은 죽은 run이 처음 적재한 노트뿐이라, 재수집이 첫 페이지를 건너뛰어도 기존 active 전부가 관측되지 않아 축소 보호(ADR-0028)가 정리를 막는다. 앞
   * 수집이 끝난 소스에서는 첫 페이지 노트가 기존 active의 일부일 뿐이라 보호가 막지 않는다 — 다시 걷기만이 지킨다.
   */
  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("앞 수집이 끝난 소스에서 첫 목록 페이지 커밋 뒤 kill -9하고 다시 수집하면")
  class KillAfterFirstPageOverCollectedSource {
    ChildJvm.ProbeResult resumed;

    @BeforeAll
    void collectThenKillThenRecollect(@TempDir Path dir) throws Exception {
      PostgresSupport.truncateAll();
      Process seed = probe(dir, "seed", "seed", "seed");
      if (!seed.waitFor(RESUME_PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS)
          || seed.exitValue() != 0) throw new IllegalStateException("given 준비 실패: 앞 수집이 끝나지 않았다");
      if (resultOf(dir, "seed").getInt("count") != READABLE_NOTES)
        throw new IllegalStateException("given 준비 실패: 앞 수집이 읽을 수 있는 노트를 모두 적재하지 않았다");
      killAtSecondListPage(dir);
      recollect(dir);
      resumed = resultOf(dir, "resume");
      // 실패한 재수집은 정리하지 않으므로 아래 단언이 공짜로 통과한다
      if (!resumed.get("status").equals("succeeded"))
        throw new IllegalStateException(
            "given 준비 실패: 재수집 run이 succeeded가 아니다: " + resumed.get("status"));
    }

    @Test
    @DisplayName("첫 페이지의 노트도 정리되지 않는다")
    void keepsFirstPageNotes() {
      // then
      assertThat(resumed.getInt("count")).isEqualTo(READABLE_NOTES);
    }

    @Test
    @DisplayName("상세가 403·404인 노트만 tombstone이다")
    void onlyUnreadableNotesAreTombstoned() {
      // then
      assertThat(resumed.getInt("tombstones")).isEqualTo(UNREADABLE_NOTES);
    }
  }
}
