package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 복원(deploy/java/bin/scos-restore.sh)의 안전장치(F10 리뷰 A I1·I2): --yes가 정확히 있어야 하고, 대상(컨테이너·DB·다른 연결
 * 수)을 먼저 보여 주고, 다른 연결이 있으면 거부한다. 덤프는 새 빈 DB에 한 트랜잭션으로 복원한 뒤에만 대상과 바꾼다 — 실패하면 원래 DB는 그대로다. 대상 지정은
 * 호출자 환경이 .env보다 우선한다. docker는 가짜다(호출과 stdin 기록, FAKE_CONNECTIONS·FAKE_RESTORE_FAIL 각본). 실제 복원은
 * {@link BackupRestoreRehearsalTest}가 본다.
 */
@Tag("process")
@DisplayName("복원 scos-restore.sh는 새 DB에 복원한 뒤에만 대상과 바꾼다(T10.1)")
class RestoreScriptTest {
  static final String SCRIPT = "scos-restore.sh";
  static final int USAGE = 2;
  static final int FAILED = 1;

  /**
   * 가짜 docker: 호출·stdin을 기록하고, 스크립트가 쓰는 모양의 호출에만 답한다 — `exec <컨테이너> psql -U <사용자> -d postgres -v
   * ON_ERROR_STOP=1 -Atc <SQL>`(연결 수·크기·대상 존재·연결 허용·이름 바꾸기 SQL), `exec <컨테이너> sh -c 'df -Pk …'`,
   * `exec <컨테이너> createdb|dropdb …`, `exec -i <컨테이너> pg_restore -U <사용자> -d <DB> --no-owner
   * --single-transaction`. 그 밖의 호출(-i 없는 pg_restore 포함)은 97로 실패한다. 각본: FAKE_CONNECTIONS(첫 연결 검사),
   * FAKE_LATE_CONNECTIONS(교체 직전 다시 검사), FAKE_DB_SIZE· FAKE_FREE_KB(여유 공간), FAKE_RESTORE_FAIL,
   * FAKE_RENAME_FAIL(모든 RENAME), FAKE_DROP_OLD_FAIL. 교체 중 실패(재확인 2 RC-1·RC-4): FAKE_LATE_FAIL(다시
   * 검사하는 질의 자체가 실패), FAKE_LATE_HOLD(다시 검사에서 records/late.held를 남기고 records/late.release가 생길 때까지 기다림
   * — 그 사이 신호를 보낸다), FAKE_FRESH_RENAME_FAIL(새 DB → 대상 이름), FAKE_REVERT_FAIL(옛 DB → 원래 이름 되돌리기).
   * FAKE_RESTORE_HOLD(pg_restore에서 records/restore.held를 남기고 records/restore.release가 생길 때까지 기다림 —
   * 교체 전 신호, F15 T15.0). FAKE_TARGET_MISSING(대상 DB가 없는 새 호스트 — 존재 검사가 0, 크기를 DB 이름으로 물으면
   * PostgreSQL처럼 "does not exist"로 실패하고 pg_database 행으로 물으면 행이 없어 0). 각본마다의 동작은 {@link
   * ScriptHarnessTest}가 본다.
   */
  static final String FAKE_DOCKER =
      """
      echo "$*" >> "$SCOS_RECORDS/docker.calls"
      unexpected() { echo "unexpected docker $*" >&2; exit 97; }
      [ "$1" = exec ] || unexpected "$@"
      if [ "$2" = -i ]; then
        [ "$#" = 10 ] && [ "$4 $5 $7 $9 ${10}" = "pg_restore -U -d --no-owner --single-transaction" ] || unexpected "$@"
        cat > "$SCOS_RECORDS/docker.stdin"
        if [ -n "${FAKE_RESTORE_HOLD-}" ]; then
          touch "$SCOS_RECORDS/restore.held"
          while [ ! -f "$SCOS_RECORDS/restore.release" ]; do sleep 0.05; done
        fi
        [ -n "${FAKE_RESTORE_FAIL-}" ] && exit 1
        exit 0
      fi
      case "$3" in
        psql)
          [ "$#" = 11 ] && [ "$4 $6 $7 $8 $9 ${10}" = "-U -d postgres -v ON_ERROR_STOP=1 -Atc" ] || unexpected "$@"
          sql="${11}"
          case "$sql" in
            *pg_stat_activity*)
              n=$(cat "$SCOS_RECORDS/activity.count" 2>/dev/null || echo 0); echo $((n + 1)) > "$SCOS_RECORDS/activity.count"
              if [ "$n" = 0 ]; then echo "${FAKE_CONNECTIONS:-0}"; exit 0; fi
              [ -n "${FAKE_LATE_FAIL-}" ] && exit 2
              if [ -n "${FAKE_LATE_HOLD-}" ]; then
                touch "$SCOS_RECORDS/late.held"
                while [ ! -f "$SCOS_RECORDS/late.release" ]; do sleep 0.05; done
              fi
              echo "${FAKE_LATE_CONNECTIONS:-0}" ;;
            *pg_database_size*)
              if [ -z "${FAKE_TARGET_MISSING-}" ]; then echo "${FAKE_DB_SIZE:-1000}"
              else
                case "$sql" in *"pg_database_size('"*) echo "ERROR:  database does not exist" >&2; exit 1 ;; esac
                echo 0
              fi ;;
            *"pg_database WHERE"*) [ -n "${FAKE_TARGET_MISSING-}" ] && echo 0 || echo 1 ;;
            *"ALLOW_CONNECTIONS false"|*"ALLOW_CONNECTIONS true") ;;
            *"RENAME TO"*)
              [ -n "${FAKE_RENAME_FAIL-}" ] && exit 1
              case "$sql" in
                *'DATABASE "scos__restore_'*) [ -n "${FAKE_FRESH_RENAME_FAIL-}" ] && exit 1 ;;
                *'DATABASE "scos__replaced_'*) [ -n "${FAKE_REVERT_FAIL-}" ] && exit 1 ;;
              esac ;;
            *) unexpected "$@" ;;
          esac ;;
        sh)
          [ "$#" = 5 ] && [ "$4" = -c ] && [ "$5" = 'df -Pk "$PGDATA" 2>/dev/null || df -Pk /var/lib/postgresql/data' ] || unexpected "$@"
          echo "Filesystem 1024-blocks Used Available Capacity Mounted on"; echo "overlay 100000000 1000 ${FAKE_FREE_KB:-99999000} 1% /var/lib/postgresql/data" ;;
        createdb) [ "$#" = 6 ] && [ "$4" = -U ] || unexpected "$@" ;;
        dropdb)
          if [ "$#" = 7 ] && [ "$4 $6" = "-U --if-exists" ]; then :
          elif [ "$#" = 6 ] && [ "$4" = -U ]; then [ -n "${FAKE_DROP_OLD_FAIL-}" ] && exit 1
          else unexpected "$@"; fi ;;
        *) unexpected "$@" ;;
      esac
      exit 0
      """;

  static final String ALLOW_ORIGINAL =
      "exec scos-pg psql -U scos -d postgres -v ON_ERROR_STOP=1 -Atc ALTER DATABASE \"scos\" ALLOW_CONNECTIONS true";

  final ScriptHarness restore = new ScriptHarness().withEnvFile().fake("docker", FAKE_DOCKER);

  @AfterEach
  void close() {
    restore.close();
  }

  Path givenDump() {
    Path dump = restore.home.resolve("scos-20261016T000000Z.dump");
    ScriptHarness.write(dump, "dump");
    return dump;
  }

  List<String> dockerCalls() {
    return restore.read(restore.records.resolve("docker.calls")).lines().toList();
  }

  ScriptHarness.Result confirmed() {
    return restore.run(SCRIPT, givenDump().toString(), "--yes");
  }

  /** 스크립트의 stderr(신호 시험처럼 Result 없이 끝난 실행). */
  String stderrLog() {
    return restore.read(restore.records.resolve(SCRIPT + ".err"));
  }

  static boolean dropsFreshCopy(String call) {
    return call.matches("exec scos-pg dropdb -U scos --if-exists scos__restore_\\d+");
  }

  static int indexOf(List<String> calls, String fragment) {
    for (int i = 0; i < calls.size(); i++) if (calls.get(i).contains(fragment)) return i;
    return -1;
  }

  @Nested
  @DisplayName("--yes 확인 없이 실행하면")
  class NotConfirmed {
    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"-y", "yes", "--no", ""})
    @DisplayName("두 번째 인자가 정확히 --yes가 아니면 사용법 오류 2로 끝난다")
    void exitsUsage(String second) {
      // when
      var result = restore.run(SCRIPT, givenDump().toString(), second);

      // then
      assertThat(result.exitCode()).isEqualTo(USAGE);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"-y", "yes", "--no", ""})
    @DisplayName("두 번째 인자가 정확히 --yes가 아니면 docker를 부르지 않는다")
    void touchesNothing(String second) {
      // when
      restore.run(SCRIPT, givenDump().toString(), second);

      // then
      assertThat(restore.records.resolve("docker.calls")).doesNotExist();
    }

    @Test
    @DisplayName("인자가 덤프 하나뿐이면 사용법 오류 2로 끝난다")
    void refusesWithoutConfirmation() {
      // when
      var result = restore.run(SCRIPT, givenDump().toString());

      // then
      assertThat(result.exitCode()).isEqualTo(USAGE);
    }
  }

  @Nested
  @DisplayName("덤프 파일이 없는 경로로 --yes 복원하면")
  class DumpMissing {
    @Test
    @DisplayName("사용법 오류 2로 끝난다")
    void exitsUsage() {
      // when
      var result = restore.run(SCRIPT, restore.home.resolve("missing.dump").toString(), "--yes");

      // then
      assertThat(result.exitCode()).isEqualTo(USAGE);
    }

    @Test
    @DisplayName("docker를 부르지 않는다")
    void touchesNothing() {
      // when
      restore.run(SCRIPT, restore.home.resolve("missing.dump").toString(), "--yes");

      // then
      assertThat(restore.records.resolve("docker.calls")).doesNotExist();
    }
  }

  @Nested
  @DisplayName("다른 연결이 없고 공간이 넉넉한 대상에 --yes로 복원하면")
  class Confirmed {
    @Test
    @DisplayName("복원 전에 대상 컨테이너·DB·다른 연결 수를 남긴다")
    void reportsTarget() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr())
          .contains("\"event\":\"restore.target\"")
          .contains("\"container\":\"scos-pg\",\"db\":\"scos\",\"otherConnections\":0");
    }

    @Test
    @DisplayName("덤프는 새 빈 DB에 한 트랜잭션으로 복원한다")
    void restoresIntoFreshDatabase() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .anyMatch(call -> call.matches("exec scos-pg createdb -U scos scos__restore_\\d+"))
          .anyMatch(
              call ->
                  call.matches(
                      "exec -i scos-pg pg_restore -U scos -d scos__restore_\\d+ --no-owner --single-transaction"));
    }

    @Test
    @DisplayName("복원이 끝나면 새 DB를 대상 이름으로 바꾼다")
    void swapsAfterRestore() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .anyMatch(
              call ->
                  call.contains("ALTER DATABASE \"scos__restore_")
                      && call.contains("RENAME TO \"scos\""));
    }

    @Test
    @DisplayName("덤프 파일 내용을 pg_restore의 표준 입력으로 넘긴다")
    void feedsDumpOnStdin() {
      // when
      confirmed();

      // then
      assertThat(restore.read(restore.records.resolve("docker.stdin"))).isEqualTo("dump");
    }

    @Test
    @DisplayName("교체 전에 원래 DB의 새 연결을 막는다")
    void blocksConnectionsBeforeSwap() {
      // when
      confirmed();

      // then: 막는 호출이 있고, 이름 바꾸기보다 앞이다
      List<String> calls = dockerCalls();
      int block = indexOf(calls, "ALTER DATABASE \"scos\" ALLOW_CONNECTIONS false");
      int rename = indexOf(calls, "RENAME TO");
      assertThat(block).isNotNegative().isLessThan(rename);
    }
  }

  @Nested
  @DisplayName("대상 DB가 없는 곳(새 호스트)에 복원하면")
  class TargetMissing {
    @BeforeEach
    void givenNoTargetDatabase() {
      restore.env.put("FAKE_TARGET_MISSING", "1");
    }

    @Test
    @DisplayName("종료 코드 0으로 끝난다")
    void exitsZero() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isZero();
    }

    @Test
    @DisplayName("복원한 새 DB를 대상 이름으로 바꾼다")
    void renamesFreshToTarget() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .anyMatch(
              call ->
                  call.matches(
                      "exec scos-pg psql .* ALTER DATABASE \"scos__restore_\\d+\" RENAME TO \"scos\""));
    }

    @Test
    @DisplayName("바꿀 원래 DB가 없으니 연결을 막거나 옛 이름으로 옮기지 않는다")
    void swapsNothing() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .noneMatch(call -> call.contains("ALLOW_CONNECTIONS"))
          .noneMatch(call -> call.contains("scos__replaced_"));
    }

    @Test
    @DisplayName("어떤 DB도 지우지 않는다")
    void dropsNothing() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).noneMatch(call -> call.contains("dropdb"));
    }

    @Test
    @DisplayName("restore.completed를 남긴다")
    void logsCompletion() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr()).contains("\"event\":\"restore.completed\"");
    }

    @Nested
    @DisplayName("pg_restore가 실패하면")
    class RestoreFails {
      @BeforeEach
      void givenRestoreFails() {
        restore.env.put("FAKE_RESTORE_FAIL", "1");
      }

      @Test
      @DisplayName("남은 DB가 없으니 kept는 빈 목록이다")
      void keepsNothing() {
        // when
        var result = confirmed();

        // then
        assertThat(result.stderr())
            .containsPattern("\"event\":\"restore.failed\".*\"kept\":\\[\\]");
      }
    }

    @Nested
    @DisplayName("새 DB 이름 바꾸기가 실패하면")
    class FreshRenameFails {
      @BeforeEach
      void givenFreshRenameFails() {
        restore.env.put("FAKE_FRESH_RENAME_FAIL", "1");
      }

      @Test
      @DisplayName("종료 코드 1로 끝난다")
      void exitsOne() {
        // when
        var result = confirmed();

        // then
        assertThat(result.exitCode()).isEqualTo(FAILED);
      }

      @Test
      @DisplayName("새 DB를 지운다")
      void dropsFresh() {
        // when
        confirmed();

        // then
        assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
      }

      @Test
      @DisplayName("남은 DB가 없으니 kept는 빈 목록이다")
      void keepsNothing() {
        // when
        var result = confirmed();

        // then
        assertThat(result.stderr())
            .containsPattern("\"event\":\"restore.failed\".*\"kept\":\\[\\]");
      }
    }
  }

  @Nested
  @DisplayName("대상 DB에 다른 연결이 있으면(데몬·MCP가 떠 있으면)")
  class InUse {
    @BeforeEach
    void givenOtherConnections() {
      restore.env.put("FAKE_CONNECTIONS", "2");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("DB를 만들거나 바꾸지 않는다")
    void changesNothing() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .noneMatch(call -> call.contains("createdb") || call.contains("pg_restore"));
    }
  }

  @Nested
  @DisplayName("pg_restore가 실패하면")
  class RestoreFails {
    @BeforeEach
    void givenRestoreFails() {
      restore.env.put("FAKE_RESTORE_FAIL", "1");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("원래 DB를 바꾸지 않고 새 DB를 지운다")
    void keepsOriginal() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .noneMatch(call -> call.contains("ALTER DATABASE"))
          .anyMatch(
              call -> call.matches("exec scos-pg dropdb -U scos --if-exists scos__restore_\\d+"));
    }
  }

  @Nested
  @DisplayName("호출자 환경으로 대상을 지정하면")
  class CallerTarget {
    @Test
    @DisplayName("호출자 환경이 .env보다 우선한다")
    void callerEnvironmentWins() {
      // given
      ScriptHarness.write(
          restore.home.resolve(".env"), "SCOS_PG_DB=from_env_file\nSCOS_PG_CONTAINER=prod-pg\n");
      ScriptHarness.setPermissions(restore.home.resolve(".env"), "rw-------");
      restore.env.put("SCOS_PG_DB", "scratch");
      restore.env.put("SCOS_PG_CONTAINER", "scratch-pg");

      // when
      var result = confirmed();

      // then
      assertThat(result.stderr()).contains("\"container\":\"scratch-pg\",\"db\":\"scratch\"");
    }

    @Test
    @DisplayName("DB 이름이 단순 식별자가 아니면 사용법 오류 2로 끝난다")
    void rejectsUnsafeDatabaseName() {
      // given
      restore.env.put("SCOS_PG_DB", "scos\"; DROP");

      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(USAGE);
    }

    @Test
    @DisplayName("38자 DB 이름은 받아들인다")
    void acceptsLongestDatabaseName() {
      // given
      restore.env.put("SCOS_PG_DB", "d".repeat(38));

      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isZero();
    }

    @Test
    @DisplayName("DB 이름이 38자를 넘으면 사용법 오류 2로 끝난다(식별자 상한 63자 − 접미사)")
    void rejectsLongDatabaseName() {
      // given
      restore.env.put("SCOS_PG_DB", "d".repeat(39));

      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(USAGE);
    }
  }

  @Nested
  @DisplayName("교체 직전에 대상 DB에 연결이 생기면")
  class LateConnection {
    @BeforeEach
    void givenLateConnection() {
      restore.env.put("FAKE_LATE_CONNECTIONS", "1");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("이름을 바꾸지 않고 새 DB를 지운다")
    void keepsOriginal() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .noneMatch(call -> call.contains("RENAME TO"))
          .anyMatch(
              call -> call.matches("exec scos-pg dropdb -U scos --if-exists scos__restore_\\d+"));
    }

    @Test
    @DisplayName("원래 DB의 연결 허용을 되돌린다")
    void reallowsConnections() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).contains(ALLOW_ORIGINAL);
    }
  }

  @Nested
  @DisplayName("이름 바꾸기가 실패하면")
  class RenameFails {
    @BeforeEach
    void givenRenameFails() {
      restore.env.put("FAKE_RENAME_FAIL", "1");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("restore.failed를 남긴다")
    void logsFailure() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr()).contains("\"event\":\"restore.failed\"");
    }

    @Test
    @DisplayName("새 DB를 지운다")
    void dropsFresh() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
    }

    @Test
    @DisplayName("원래 DB의 연결 허용을 되돌린다")
    void reallowsConnections() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).contains(ALLOW_ORIGINAL);
    }
  }

  @Nested
  @DisplayName("옛 DB 삭제만 실패하면")
  class DropOldFails {
    @BeforeEach
    void givenDropOldFails() {
      restore.env.put("FAKE_DROP_OLD_FAIL", "1");
    }

    @Test
    @DisplayName("교체는 성공이라 종료 코드 0이다")
    void exitsZero() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isZero();
    }

    @Test
    @DisplayName("남은 옛 DB 이름을 경고로 남긴다")
    void warnsWithRemainingName() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr())
          .containsPattern("\"event\":\"restore.old_database_left\".*scos__replaced_\\d+");
    }
  }

  @Nested
  @DisplayName("연결을 막은 뒤 다시 검사하는 질의가 실패하면")
  class LateCheckFails {
    @BeforeEach
    void givenLateCheckFails() {
      restore.env.put("FAKE_LATE_FAIL", "1");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("원래 DB의 연결 허용을 되돌린다")
    void reallowsConnections() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).contains(ALLOW_ORIGINAL);
    }

    @Test
    @DisplayName("새 DB를 지운다")
    void dropsFresh() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
    }

    @Test
    @DisplayName("restore.failed를 남긴다")
    void logsFailure() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr()).contains("\"event\":\"restore.failed\"");
    }
  }

  /** 가짜가 hold 파일을 남기고 멈춘 복원에 TERM을 보내고, release 뒤 끝날 때까지 기다린다. 종료 코드를 돌려준다. */
  int terminateHeld(Process p, String release) throws InterruptedException {
    ScriptHarness.signal(p, "TERM");
    ScriptHarness.write(restore.records.resolve(release), "");
    if (!p.waitFor(ScriptHarness.PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("복원 스크립트가 시간 안에 끝나지 않았다");
    return p.exitValue();
  }

  @Nested
  @DisplayName("교체 전 pg_restore 중에 TERM을 받으면")
  class TerminatedDuringPgRestore {
    Process p;

    @BeforeEach
    void givenHeldInPgRestore() {
      restore.env.put("FAKE_RESTORE_HOLD", "1");
      p = restore.start(SCRIPT, givenDump().toString(), "--yes");
      restore.awaitFile(restore.records.resolve("restore.held"));
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() throws InterruptedException {
      // when
      int exitCode = terminateHeld(p, "restore.release");

      // then
      assertThat(exitCode).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("새 DB를 지운다")
    void dropsFresh() throws InterruptedException {
      // when
      terminateHeld(p, "restore.release");

      // then
      assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
    }

    @Test
    @DisplayName("원래 DB를 건드리지 않는다")
    void leavesOriginal() throws InterruptedException {
      // when
      terminateHeld(p, "restore.release");

      // then
      assertThat(dockerCalls()).noneMatch(call -> call.contains("ALTER DATABASE"));
    }

    @Test
    @DisplayName("restore.failed를 남긴다")
    void logsFailure() throws InterruptedException {
      // when
      terminateHeld(p, "restore.release");

      // then
      assertThat(stderrLog()).contains("\"event\":\"restore.failed\"");
    }
  }

  @Nested
  @DisplayName("연결을 막은 뒤 TERM을 받으면")
  class TerminatedDuringSwap {
    Process p;

    @BeforeEach
    void givenHeldInLateCheck() {
      restore.env.put("FAKE_LATE_HOLD", "1");
      p = restore.start(SCRIPT, givenDump().toString(), "--yes");
      restore.awaitFile(restore.records.resolve("late.held"));
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() throws InterruptedException {
      // when
      int exitCode = terminateHeld(p, "late.release");

      // then
      assertThat(exitCode).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("원래 DB의 연결 허용을 되돌린다")
    void reallowsConnections() throws InterruptedException {
      // when
      terminateHeld(p, "late.release");

      // then
      assertThat(dockerCalls()).contains(ALLOW_ORIGINAL);
    }

    @Test
    @DisplayName("새 DB를 지운다")
    void dropsFresh() throws InterruptedException {
      // when
      terminateHeld(p, "late.release");

      // then
      assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
    }

    @Test
    @DisplayName("restore.failed를 남긴다")
    void logsFailure() throws InterruptedException {
      // when
      terminateHeld(p, "late.release");

      // then
      assertThat(stderrLog()).contains("\"event\":\"restore.failed\"");
    }
  }

  @Nested
  @DisplayName("새 DB 이름 바꾸기만 실패하고 되돌리기는 되면")
  class FreshRenameFails {
    @BeforeEach
    void givenFreshRenameFails() {
      restore.env.put("FAKE_FRESH_RENAME_FAIL", "1");
    }

    @Test
    @DisplayName("원래 DB를 원래 이름으로 되돌린다")
    void revertsOriginalName() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls())
          .anyMatch(
              call ->
                  call.contains("ALTER DATABASE \"scos__replaced_")
                      && call.endsWith("RENAME TO \"scos\""));
    }

    @Test
    @DisplayName("원래 DB의 연결 허용을 되돌린다")
    void reallowsConnections() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).contains(ALLOW_ORIGINAL);
    }

    @Test
    @DisplayName("새 DB를 지운다")
    void dropsFresh() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).anyMatch(RestoreScriptTest::dropsFreshCopy);
    }
  }

  @Nested
  @DisplayName("새 DB 이름 바꾸기와 되돌리기가 모두 실패하면")
  class RenameAndRevertFail {
    @BeforeEach
    void givenBothFail() {
      restore.env.put("FAKE_FRESH_RENAME_FAIL", "1");
      restore.env.put("FAKE_REVERT_FAIL", "1");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("복원한 새 DB를 지우지 않는다")
    void keepsRestoredCopy() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).noneMatch(RestoreScriptTest::dropsFreshCopy);
    }

    @Test
    @DisplayName("남은 두 DB 이름을 kept로 남긴다")
    void logsBothNames() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr())
          .containsPattern(
              "\"event\":\"restore.failed\".*\"kept\":\\[\"scos__replaced_\\d+\",\"scos__restore_\\d+\"\\]");
    }

    @Test
    @DisplayName("수동 복구 명령을 남긴다")
    void logsManualRecovery() {
      // when
      var result = confirmed();

      // then
      assertThat(result.stderr())
          .containsPattern(
              "\"recovery\":\"ALTER DATABASE \\\\\"scos__replaced_\\d+\\\\\" RENAME TO \\\\\"scos\\\\\"");
    }
  }

  @Nested
  @DisplayName("여유 공간이 DB 크기의 2배보다 작으면")
  class LowDisk {
    @BeforeEach
    void givenTenGigabytesOnAlmostFullDisk() {
      restore.env.put("FAKE_DB_SIZE", String.valueOf(10L * 1024 * 1024 * 1024));
      restore.env.put("FAKE_FREE_KB", "1000");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("새 DB를 만들지 않는다")
    void createsNothing() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).noneMatch(call -> call.contains("createdb"));
    }

    @Test
    @DisplayName("크기의 1.5배가 있어도 거부한다")
    void requiresTwiceTheDatabaseSize() {
      // given: 1.5배 — 복원 사본 하나는 들어가지만 WAL·인덱스 빌드 임시 공간이 모자랄 수 있다
      restore.env.put("FAKE_DB_SIZE", String.valueOf(1000L * 1024));
      restore.env.put("FAKE_FREE_KB", "1500");

      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }
  }

  @Test
  @DisplayName("여유 공간이 DB 크기의 정확히 2배이면 받아들여 0으로 끝난다")
  void acceptsExactlyTwiceTheDatabaseSize() {
    // given: DB 1000KiB, 여유 2000KiB — 경계는 "2배보다 작으면 거부"
    restore.env.put("FAKE_DB_SIZE", String.valueOf(1000L * 1024));
    restore.env.put("FAKE_FREE_KB", "2000");

    // when
    var result = confirmed();

    // then
    assertThat(result.exitCode()).isZero();
  }

  @Nested
  @DisplayName("여유 공간을 df 출력에서 숫자로 읽지 못하면")
  class UnreadableFreeSpace {
    @BeforeEach
    void givenDfReportsNoNumber() {
      restore.env.put("FAKE_FREE_KB", "n/a");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = confirmed();

      // then
      assertThat(result.exitCode()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("새 DB를 만들지 않는다")
    void createsNothing() {
      // when
      confirmed();

      // then
      assertThat(dockerCalls()).noneMatch(call -> call.contains("createdb"));
    }

    @Test
    @DisplayName("restore.refused에 읽은 값을 남긴다")
    void logsRefusal() {
      // when
      var result = confirmed();

      // then: 셸 산술 오류로 우연히 끝나는 것과 구별된다 — 거부는 운영자가 읽는 이유와 함께다
      assertThat(result.stderr())
          .containsPattern("\"event\":\"restore.refused\".*\"freeKb\":\"n/a\"");
    }
  }
}
