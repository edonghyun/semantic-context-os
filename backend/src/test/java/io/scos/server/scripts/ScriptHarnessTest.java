package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 셸 테스트가 믿는 가짜 명령 자체의 계약(F15 T15.2, tr-ops H2): 가짜가 틀리면 래퍼 테스트가 모두 거짓말을 한다(H1이 그 예). 가짜를 래퍼 없이 직접
 * 실행해 각본대로 답하는지 본다. GNU에서의 가짜 stat은 GnuWrapperTest가 본다.
 */
@Tag("process")
@DisplayName("셸 테스트의 가짜 명령은 각본대로 답한다")
class ScriptHarnessTest {
  static final int SIGTERM_EXIT = 143;
  static final int UNEXPECTED_CALL = 97;

  /** "끝나지 않는다"를 보는 고정 관찰 시간(TESTING §7) — 짧게 의도한 상한. */
  static final Duration SHORT_LIMIT = Duration.ofMillis(500);

  final ScriptHarness harness = new ScriptHarness();

  @AfterEach
  void close() {
    harness.close();
  }

  @Nested
  @DisplayName("가짜 java를 실행하면")
  class FakeJava {
    @BeforeEach
    void givenFakeJava() {
      harness.fakeJava();
    }

    @Test
    @DisplayName("받은 인자를 줄마다 java.args에 남긴다")
    void recordsArgs() {
      // when
      harness.runFake("java", "-jar", "app.jar", "daemon");

      // then
      assertThat(harness.read(harness.records.resolve("java.args")))
          .isEqualTo("-jar\napp.jar\ndaemon\n");
    }

    @Test
    @DisplayName("FAKE_JAVA_EXIT로 끝난다")
    void exitsWithScriptedCode() {
      // given
      harness.env.put("FAKE_JAVA_EXIT", "70");

      // when
      var result = harness.runFake("java");

      // then
      assertThat(result.exitCode()).isEqualTo(70);
    }

    @Nested
    @DisplayName("FAKE_JAVA_WAIT로 기다리는 중에 TERM을 받으면")
    class TermWhileWaiting {
      Process java;

      @BeforeEach
      void givenWaiting() {
        harness.env.put("FAKE_JAVA_WAIT", "1");
        java = harness.startFake("java");
        harness.awaitFile(harness.records.resolve("java.started"));
      }

      /** TERM을 보내고 끝나기를 기다린다 — 상한 안에 끝났는지를 돌려준다. */
      boolean terminate() throws InterruptedException {
        ScriptHarness.signal(java, "TERM");
        return java.waitFor(ScriptHarness.PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS);
      }

      @Test
      @DisplayName("상한 안에 끝난다")
      void ends() throws InterruptedException {
        // when
        boolean ended = terminate();

        // then
        assertThat(ended).isTrue();
      }

      @Test
      @DisplayName("143으로 끝난다")
      void exits143() throws InterruptedException {
        // when
        terminate();

        // then
        assertThat(java.exitValue()).isEqualTo(SIGTERM_EXIT);
      }

      @Test
      @DisplayName("java.term을 남긴다")
      void recordsTerm() throws InterruptedException {
        // when
        terminate();

        // then
        assertThat(harness.records.resolve("java.term")).exists();
      }
    }
  }

  @Nested
  @DisplayName("가짜 GNU stat을 실행하면")
  class FakeGnuStat {
    @BeforeEach
    void givenFakeStat() {
      harness.gnuStat();
    }

    @Test
    @DisplayName("-c '%a'에 파일 권한 8진수를 낸다")
    void reportsMode() throws Exception {
      // given
      Path file = Files.writeString(harness.home.resolve("f"), "");
      ScriptHarness.setPermissions(file, "rw-r-----");

      // when
      var result = harness.runFake("stat", "-c", "%a", file.toString());

      // then
      assertThat(result.stdout()).isEqualTo("640\n");
    }

    @Test
    @DisplayName("특수 비트(setuid 4755)도 버리지 않고 낸다")
    void keepsSpecialBits() throws Exception {
      // given
      Path file = Files.writeString(harness.home.resolve("suid"), "");
      if (new ProcessBuilder("chmod", "4755", file.toString()).start().waitFor() != 0)
        throw new IllegalStateException("given 준비 실패: chmod 4755가 실패했다");

      // when
      var result = harness.runFake("stat", "-c", "%a", file.toString());

      // then
      assertThat(result.stdout()).isEqualTo("4755\n");
    }

    @Test
    @DisplayName("-f는 파일 시스템 옵션이라 1로 실패한다(GNU처럼)")
    void fileSystemOptionFails() {
      // when
      var result = harness.runFake("stat", "-f", "%Lp", "x");

      // then
      assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("-f는 GNU처럼 권한 대신 파일 시스템 정보를 stdout에 낸다")
    void fileSystemOptionPrintsFileSystem() {
      // when
      var result = harness.runFake("stat", "-f", "%Lp", "x");

      // then: 래퍼가 이 출력을 권한으로 읽으면 600이 아니다 — BSD 문법으로 넘어가도 GNU에서는 권한을 얻지 못한다
      assertThat(result.stdout())
          .isEqualTo("  File: \"x\"\n    ID: 0 Namelen: 255 Type: overlayfs\n");
    }
  }

  @Nested
  @DisplayName("백업 테스트의 가짜 docker를 실행하면")
  class BackupDocker {
    @BeforeEach
    void givenFakeDocker() {
      harness.fake("docker", BackupScriptTest.FAKE_DOCKER);
    }

    @Test
    @DisplayName("정확한 pg_dump 인자에 덤프를 낸다")
    void dumpsOnExactArgs() {
      // when
      var result =
          harness.runFake(
              "docker", "exec", "scos-pg", "pg_dump", "-U", "scos", "-d", "scos", "-Fc");

      // then
      assertThat(result.stdout()).isEqualTo("partial dump");
    }

    @Test
    @DisplayName("-i 없는 pg_restore --list는 97로 실패한다")
    void rejectsRestoreWithoutStdin() {
      // when
      var result = harness.runFake("docker", "exec", "scos-pg", "pg_restore", "--list");

      // then
      assertThat(result.exitCode()).isEqualTo(UNEXPECTED_CALL);
    }
  }

  @Nested
  @DisplayName("복원 테스트의 가짜 docker를 실행하면")
  class RestoreDocker {
    static final String ACTIVITY =
        "SELECT count(*) FROM pg_stat_activity WHERE datname = 'scos' AND pid <> pg_backend_pid()";
    static final String[] PSQL = {
      "exec", "scos-pg", "psql", "-U", "scos", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-Atc"
    };
    static final String[] PG_RESTORE = {
      "exec",
      "-i",
      "scos-pg",
      "pg_restore",
      "-U",
      "scos",
      "-d",
      "scos__restore_20261016000000",
      "--no-owner",
      "--single-transaction"
    };

    @BeforeEach
    void givenFakeDocker() {
      harness.fake("docker", RestoreScriptTest.FAKE_DOCKER);
      harness.env.put("FAKE_CONNECTIONS", "2");
      harness.env.put("FAKE_LATE_CONNECTIONS", "5");
    }

    /** 복원 스크립트의 psql_admin과 같은 모양의 호출. */
    ScriptHarness.Result psql(String sql) {
      return harness.runFake("docker", withSql(sql));
    }

    ScriptHarness.Result activity() {
      return psql(ACTIVITY);
    }

    /** 표준 입력을 닫은 pg_restore 호출을 띄운다(끝을 기다리지 않는다). */
    Process startPgRestore() throws IOException {
      Process p = harness.startFake("docker", PG_RESTORE);
      p.getOutputStream().close();
      return p;
    }

    @Test
    @DisplayName("첫 연결 검사는 FAKE_CONNECTIONS를 낸다")
    void firstCheckUsesConnections() {
      // when
      var first = activity();

      // then
      assertThat(first.stdout()).isEqualTo("2\n");
    }

    @Test
    @DisplayName("두 번째 연결 검사는 FAKE_LATE_CONNECTIONS를 낸다")
    void secondCheckUsesLateConnections() {
      // given
      activity();

      // when
      var second = activity();

      // then
      assertThat(second.stdout()).isEqualTo("5\n");
    }

    @Test
    @DisplayName("FAKE_FRESH_RENAME_FAIL은 새 DB의 이름 바꾸기만 실패시킨다")
    void freshRenameFailsOnlyFresh() {
      // given
      harness.env.put("FAKE_FRESH_RENAME_FAIL", "1");

      // when
      var original = psql("ALTER DATABASE \"scos\" RENAME TO \"scos__replaced_20261016000000\"");

      // then
      assertThat(original.exitCode()).isZero();
    }

    @Test
    @DisplayName("FAKE_FRESH_RENAME_FAIL이면 새 DB의 이름 바꾸기는 1로 실패한다")
    void freshRenameFails() {
      // given
      harness.env.put("FAKE_FRESH_RENAME_FAIL", "1");

      // when
      var fresh = psql("ALTER DATABASE \"scos__restore_20261016000000\" RENAME TO \"scos\"");

      // then
      assertThat(fresh.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("스크립트가 쓰지 않는 호출은 97로 실패한다")
    void rejectsUnexpectedCall() {
      // when
      var result = harness.runFake("docker", "ps");

      // then
      assertThat(result.exitCode()).isEqualTo(UNEXPECTED_CALL);
    }

    @Test
    @DisplayName("-i 없는 pg_restore는 97로 실패한다")
    void rejectsRestoreWithoutStdin() {
      // given: 표준 입력을 넘기지 않는 모양(실제 docker는 -i 없이 stdin을 전달하지 않는다)
      String[] withoutStdin =
          Arrays.stream(PG_RESTORE).filter(a -> !a.equals("-i")).toArray(String[]::new);

      // when
      var result = harness.runFake("docker", withoutStdin);

      // then
      assertThat(result.exitCode()).isEqualTo(UNEXPECTED_CALL);
    }

    @Test
    @DisplayName("FAKE_LATE_FAIL이면 두 번째 연결 검사가 2로 실패한다")
    void lateCheckFails() {
      // given
      harness.env.put("FAKE_LATE_FAIL", "1");
      activity();

      // when
      var second = activity();

      // then
      assertThat(second.exitCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("FAKE_REVERT_FAIL이면 옛 DB를 원래 이름으로 되돌리기가 1로 실패한다")
    void revertFails() {
      // given
      harness.env.put("FAKE_REVERT_FAIL", "1");

      // when
      var revert = psql("ALTER DATABASE \"scos__replaced_20261016000000\" RENAME TO \"scos\"");

      // then
      assertThat(revert.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("FAKE_DROP_OLD_FAIL이면 옛 DB 삭제가 1로 실패한다")
    void dropOldFails() {
      // given
      harness.env.put("FAKE_DROP_OLD_FAIL", "1");

      // when
      var drop =
          harness.runFake(
              "docker", "exec", "scos-pg", "dropdb", "-U", "scos", "scos__replaced_20261016000000");

      // then
      assertThat(drop.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("FAKE_LATE_HOLD이면 두 번째 연결 검사가 late.release 전에는 끝나지 않는다")
    void lateCheckHolds() throws Exception {
      // given
      harness.env.put("FAKE_LATE_HOLD", "1");
      activity();
      Process second = harness.startFake("docker", withSql(ACTIVITY));
      harness.awaitFile(harness.records.resolve("late.held"));

      // when
      boolean ended = second.waitFor(SHORT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);

      // then
      assertThat(ended).isFalse();
    }

    @Test
    @DisplayName("FAKE_RESTORE_HOLD이면 pg_restore가 restore.release 전에는 끝나지 않는다")
    void restoreHolds() throws Exception {
      // given
      harness.env.put("FAKE_RESTORE_HOLD", "1");
      Process restore = startPgRestore();
      harness.awaitFile(harness.records.resolve("restore.held"));

      // when
      boolean ended = restore.waitFor(SHORT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);

      // then
      assertThat(ended).isFalse();
    }

    @Test
    @DisplayName("FAKE_RESTORE_HOLD이면 release 뒤에 pg_restore가 0으로 끝난다")
    void restoreReleases() throws Exception {
      // given
      harness.env.put("FAKE_RESTORE_HOLD", "1");
      Process restore = startPgRestore();
      harness.awaitFile(harness.records.resolve("restore.held"));

      // when
      ScriptHarness.write(harness.records.resolve("restore.release"), "");
      if (!restore.waitFor(ScriptHarness.PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new IllegalStateException("가짜 pg_restore가 release 뒤에도 끝나지 않았다");

      // then
      assertThat(restore.exitValue()).isZero();
    }

    @Test
    @DisplayName("FAKE_TARGET_MISSING이면 대상 존재 검사가 0을 낸다")
    void targetMissingCountsZero() {
      // given
      harness.env.put("FAKE_TARGET_MISSING", "1");

      // when
      var exists = psql("SELECT count(*) FROM pg_database WHERE datname = 'scos'");

      // then
      assertThat(exists.stdout()).isEqualTo("0\n");
    }

    @Test
    @DisplayName("FAKE_TARGET_MISSING이면 크기를 DB 이름으로 물을 때 PostgreSQL처럼 1로 실패한다")
    void targetMissingSizeByNameFails() {
      // given
      harness.env.put("FAKE_TARGET_MISSING", "1");

      // when
      var size = psql("SELECT COALESCE((SELECT pg_database_size('scos')), 0)");

      // then
      assertThat(size.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("FAKE_TARGET_MISSING이면 크기를 pg_database 행으로 물을 때 행이 없어 0을 낸다")
    void targetMissingSizeByRowIsZero() {
      // given
      harness.env.put("FAKE_TARGET_MISSING", "1");

      // when
      var size =
          psql(
              "SELECT COALESCE((SELECT pg_database_size(oid) FROM pg_database WHERE datname = 'scos'), 0)");

      // then
      assertThat(size.stdout()).isEqualTo("0\n");
    }

    static String[] withSql(String sql) {
      String[] args = Arrays.copyOf(PSQL, PSQL.length + 1);
      args[PSQL.length] = sql;
      return args;
    }
  }
}
