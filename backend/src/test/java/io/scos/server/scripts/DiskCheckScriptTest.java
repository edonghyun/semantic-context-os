package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 디스크 감시(deploy/java/bin/scos-disk-check.sh, T10.1): 감시 경로의 사용률이 임계치(기본 85%)를 넘으면 error
 * disk.usage.high를 남기고 종료 코드 1로 끝난다. 2026-10-15 ENOSPC로 Docker가 멈춘 이력이 계기다. 읽지 못하는 경로가 있어도 나머지 경로를
 * 끝까지 보고 2로 끝난다(B9) — 그 뒤에 숨은 가득 찬 디스크를 놓치지 않으려는 것이다. df는 가짜다(FAKE_DF_USED %).
 */
@Tag("process")
@DisplayName("디스크 감시 scos-disk-check.sh는 사용률이 임계치를 넘으면 1로 알린다(T10.1)")
class DiskCheckScriptTest {
  static final String SCRIPT = "scos-disk-check.sh";
  static final int OVER_THRESHOLD = 1;
  static final int UNREADABLE = 2;

  /** 임계치 설정이 잘못됐을 때(disk.threshold.invalid) — 읽기 실패와 같은 2다. */
  static final int INVALID_SETTING = 2;

  final ScriptHarness check =
      new ScriptHarness()
          .fake(
              "df",
              """
              echo "$*" >> "$SCOS_RECORDS/df.calls"
              path="${@: -1}"
              [ "$path" = "${FAKE_DF_FAIL_PATH-}" ] && { echo "df: $path: No such file" >&2; exit 1; }
              echo "Filesystem 512-blocks Used Available Capacity Mounted on"
              echo "${FAKE_DF_NAME:-/dev/disk3s1} 1000000 900000 100000 ${FAKE_DF_USED}% /Volumes/Data Disk"
              """);

  @AfterEach
  void close() {
    check.close();
  }

  @Nested
  @DisplayName("사용률(91%)이 임계치(85%)를 넘으면")
  class Over {
    @BeforeEach
    void givenHighUsage() {
      check.env.put("FAKE_DF_USED", "91");
    }

    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = check.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isEqualTo(OVER_THRESHOLD);
    }

    @Test
    @DisplayName("error disk.usage.high를 사용률·임계치와 함께 남긴다")
    void logsHighUsage() {
      // when
      var result = check.run(SCRIPT);

      // then
      assertThat(result.stderr())
          .contains("\"level\":\"error\",\"event\":\"disk.usage.high\"")
          .contains("\"usedPercent\":91,\"maxPercent\":85");
    }
  }

  @Nested
  @DisplayName("사용률(10%)이 임계치 안이면")
  class Under {
    @BeforeEach
    void givenLowUsage() {
      check.env.put("FAKE_DF_USED", "10");
    }

    @Test
    @DisplayName("감시 경로마다 df -P에 그 경로를 넘긴다")
    void passesEachPath() {
      // given
      check.env.put("SCOS_DISK_PATHS", "/data one:/data two");

      // when
      check.run(SCRIPT);

      // then
      assertThat(check.read(check.records.resolve("df.calls")).lines())
          .containsExactly("-P /data one", "-P /data two");
    }

    @Test
    @DisplayName("파일 시스템 이름에 공백이 있어도 사용률 열을 읽는다")
    void spacedFilesystemName() {
      // given: 5열을 읽는 순진한 구현이면 100000을 사용률로 읽는다
      check.env.put("FAKE_DF_NAME", "map auto_home");

      // when
      var result = check.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isZero();
    }
  }

  @Test
  @DisplayName("임계치와 같으면 넘지 않은 것이다(종료 코드 0)")
  void atThresholdIsOk() {
    // given
    check.env.put("FAKE_DF_USED", "85");

    // when
    var result = check.run(SCRIPT);

    // then
    assertThat(result.exitCode()).isZero();
  }

  @Test
  @DisplayName("임계치는 SCOS_DISK_MAX_PERCENT를 따른다")
  void thresholdFromVariable() {
    // given
    check.env.put("FAKE_DF_USED", "60");
    check.env.put("SCOS_DISK_MAX_PERCENT", "50");

    // when
    var result = check.run(SCRIPT);

    // then
    assertThat(result.exitCode()).isEqualTo(OVER_THRESHOLD);
  }

  @Test
  @DisplayName("임계치가 숫자가 아니면 종료 코드 2로 끝난다")
  void nonNumericThresholdExitsTwo() {
    // given
    check.env.put("FAKE_DF_USED", "99");
    check.env.put("SCOS_DISK_MAX_PERCENT", "85%");

    // when
    var result = check.run(SCRIPT);

    // then
    assertThat(result.exitCode()).isEqualTo(INVALID_SETTING);
  }

  @Nested
  @DisplayName("읽지 못하는 경로 뒤에 넘친 경로(91%)가 있으면")
  class UnreadableThenOver {
    @BeforeEach
    void givenUnreadableThenHighUsage() {
      check.env.put("SCOS_DISK_PATHS", "/gone:/full");
      check.env.put("FAKE_DF_FAIL_PATH", "/gone");
      check.env.put("FAKE_DF_USED", "91");
    }

    @Test
    @DisplayName("읽지 못한 2가 넘친 1보다 앞선다")
    void unreadableWinsOverThreshold() {
      // when
      var result = check.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isEqualTo(UNREADABLE);
    }

    @Test
    @DisplayName("멈추지 않고 뒤 경로의 disk.usage.high도 남긴다")
    void keepsCheckingLaterPaths() {
      // when
      var result = check.run(SCRIPT);

      // then
      assertThat(result.stderr())
          .contains("\"event\":\"disk.usage.unreadable\"")
          .contains("\"event\":\"disk.usage.high\"")
          .contains("\"path\":\"/full\",\"usedPercent\":91");
    }
  }

  @Test
  @DisplayName("df로 읽지 못하는 경로면 종료 코드 2로 끝난다")
  void unreadableExitsTwo() {
    // given
    check.env.put("SCOS_DISK_PATHS", "/gone");
    check.env.put("FAKE_DF_FAIL_PATH", "/gone");

    // when
    var result = check.run(SCRIPT);

    // then
    assertThat(result.exitCode()).isEqualTo(UNREADABLE);
  }
}
