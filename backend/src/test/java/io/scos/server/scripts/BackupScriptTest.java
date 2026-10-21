package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 백업(deploy/java/bin/scos-backup.sh)의 계약: PostgreSQL 컨테이너 안의 pg_dump(-Fc)로
 * backups/scos-<UTC>.dump(600)를 남기고, 보존 기간(기본 14일)보다 오래된 덤프를 지운다. 덤프가 실패하면 부분 파일을 남기지 않는다. docker는
 * 가짜다 — 실제 덤프·복원은 {@link BackupRestoreRehearsalTest}가 본다.
 */
@Tag("process")
@DisplayName("백업 scos-backup.sh는 읽히는 덤프만 600으로 남기고 오래된 덤프를 지운다")
class BackupScriptTest {
  static final String SCRIPT = "scos-backup.sh";
  static final Duration OLDER_THAN_RETENTION = Duration.ofDays(20);
  static final Duration RECENT = Duration.ofDays(1);

  /** 보존 3일(SCOS_BACKUP_RETENTION_DAYS)은 넘고 기본 14일 안인 나이 — 일 단위 경계에서 떨어진 값. */
  static final Duration PAST_THREE_DAY_RETENTION = Duration.ofDays(5);

  /**
   * 정확한 인자에만 답한다(tr-ops H9 — 실제 docker는 -i 없이 stdin을 넘기지 않고, --list 없는 pg_restore는 SQL을 내고 성공한다):
   * `exec scos-pg pg_dump -U scos -d scos -Fc`는 "partial dump"를 쓰고, `exec -i scos-pg pg_restore
   * --list`는 받은 덤프를 records/restore-list.stdin에 남기고 성공한다. 그 밖의 호출은 97로 실패한다. 각본: FAKE_DOCKER_FAIL =
   * pg_dump가 일부를 쓰고 실패, FAKE_DUMP_EMPTY = pg_dump가 아무것도 쓰지 않고 성공, FAKE_LIST_FAIL = 덤프를 읽을 수 없다.
   */
  static final String FAKE_DOCKER =
      """
      echo "$*" >> "$SCOS_RECORDS/docker.calls"
      case "$*" in
        "exec scos-pg pg_dump -U scos -d scos -Fc")
          [ -n "${FAKE_DUMP_EMPTY-}" ] && exit 0
          printf 'partial'; [ -n "${FAKE_DOCKER_FAIL-}" ] && exit 1; printf ' dump' ;;
        "exec -i scos-pg pg_restore --list")
          cat > "$SCOS_RECORDS/restore-list.stdin"
          [ -n "${FAKE_LIST_FAIL-}" ] && exit 1 ;;
        *) echo "unexpected docker $*" >&2; exit 97 ;;
      esac
      exit 0
      """;

  final ScriptHarness backup = new ScriptHarness().withEnvFile().fake("docker", FAKE_DOCKER);

  @AfterEach
  void close() {
    backup.close();
  }

  Path backups() {
    return backup.home.resolve("backups");
  }

  List<Path> dumps() {
    try (Stream<Path> files = Files.list(backups())) {
      return files.sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Path givenDumpAged(String name, Duration age) {
    try {
      Files.createDirectories(backups());
      Path file = backups().resolve(name);
      ScriptHarness.write(file, "old");
      Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  @DisplayName("pg_dump가 읽을 수 있는 덤프를 내면")
  class DumpSucceeds {
    @Test
    @DisplayName("덤프를 backups/scos-<UTC>.dump로 남긴다")
    void writesDump() {
      // when
      backup.run(SCRIPT);

      // then
      assertThat(dumps())
          .singleElement()
          .satisfies(
              dump ->
                  assertThat(dump.getFileName().toString()).matches("scos-\\d{8}T\\d{6}Z\\.dump"));
    }

    @Test
    @DisplayName("덤프 파일은 600이다")
    void dumpIs600() {
      // when
      backup.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(dumps().getFirst())).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("컨테이너 안에서 pg_dump -Fc를 부른다")
    void dumpsInsideContainer() {
      // when
      backup.run(SCRIPT);

      // then
      assertThat(backup.read(backup.records.resolve("docker.calls")))
          .contains("exec scos-pg pg_dump -U scos -d scos -Fc");
    }

    @Test
    @DisplayName("남기기 전에 덤프 자체를 pg_restore --list로 읽어 본다")
    void verifiesDumpReadable() {
      // when
      backup.run(SCRIPT);

      // then
      assertThat(backup.read(backup.records.resolve("restore-list.stdin")))
          .isEqualTo("partial dump");
    }

    @Test
    @DisplayName("이전 백업이 죽어 남긴 .partial을 시작할 때 지운다")
    void removesStalePartial() {
      // given
      Path stale = givenDumpAged("scos-20261015T000000Z.dump.partial", RECENT);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(stale).doesNotExist();
    }
  }

  @Nested
  @DisplayName("보존 정리를 할 때(기본 14일)")
  class Retention {
    @Test
    @DisplayName("기간이 지난 덤프를 지운다")
    void prunesOld() {
      // given
      Path old = givenDumpAged("scos-20260901T000000Z.dump", OLDER_THAN_RETENTION);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(old).doesNotExist();
    }

    @Test
    @DisplayName("기간 안의 덤프는 남긴다")
    void keepsRecent() {
      // given
      Path recent = givenDumpAged("scos-20261015T000000Z.dump", RECENT);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(recent).exists();
    }

    @Test
    @DisplayName("이름 규칙 밖의 파일은 건드리지 않는다")
    void ignoresOtherFiles() {
      // given
      Path other = givenDumpAged("notes.txt", OLDER_THAN_RETENTION);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(other).exists();
    }

    @Test
    @DisplayName("보존 기간은 SCOS_BACKUP_RETENTION_DAYS를 따른다")
    void retentionFromVariable() {
      // given: 보존 3일이면 5일 된 덤프는 기간이 지났다(기본 14일이면 남는다) — 일 단위 경계에서 떨어진 값
      backup.env.put("SCOS_BACKUP_RETENTION_DAYS", "3");
      Path fiveDaysOld = givenDumpAged("scos-20261013T000000Z.dump", PAST_THREE_DAY_RETENTION);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(fiveDaysOld).doesNotExist();
    }
  }

  @Nested
  @DisplayName("덤프가 쓸모없으면(pg_dump 실패·빈 덤프·읽을 수 없는 덤프)")
  class BadDump {
    static Stream<Named<String>> badDumps() {
      return Stream.of(
          Named.of("pg_dump 실패", "FAKE_DOCKER_FAIL"),
          Named.of("빈 덤프", "FAKE_DUMP_EMPTY"),
          Named.of("읽을 수 없는 덤프", "FAKE_LIST_FAIL"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badDumps")
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne(String scenario) {
      // given
      backup.env.put(scenario, "1");

      // when
      var result = backup.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badDumps")
    @DisplayName("덤프 파일을 남기지 않는다")
    void leavesNoDump(String scenario) {
      // given
      backup.env.put(scenario, "1");

      // when
      backup.run(SCRIPT);

      // then
      assertThat(dumps()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badDumps")
    @DisplayName("보존 기간이 지난 옛 덤프도 지우지 않는다(새 덤프가 없으면 마지막 복구 수단이다)")
    void keepsOldDumps(String scenario) {
      // given
      backup.env.put(scenario, "1");
      Path old = givenDumpAged("scos-20260901T000000Z.dump", OLDER_THAN_RETENTION);

      // when
      backup.run(SCRIPT);

      // then
      assertThat(old).exists();
    }
  }
}
