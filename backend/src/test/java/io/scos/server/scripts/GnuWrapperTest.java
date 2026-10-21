package io.scos.server.scripts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * 래퍼의 GNU 이식성(리뷰 B I1): 운영 이미지와 같은 Debian 컨테이너(GNU coreutils·bash 5)에서 실제 래퍼를 돌린다. CI(ubuntu)도
 * GNU다. java는 인자를 기록하는 가짜다. macOS 셸 테스트는 BSD 도구만 지나므로 GNU 갈래는 여기서만 증명된다.
 */
@Tag("db")
@DisplayName("운영 래퍼는 GNU 도구(Debian)에서도 macOS와 같게 돈다")
class GnuWrapperTest {
  static final String BIN = "/opt/scos/bin";
  static final String HOME = "/tmp/scos-home";
  static final String HOME_FAKE_STAT = "/tmp/scos-home-fake-stat";
  static final String FAKE_BIN = "/tmp/fake-bin";

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("GNU 도구(Debian)에서 래퍼를 실행하면")
  class OnDebian {

    final GenericContainer<?> debian =
        new GenericContainer<>(PostgresSupport.IMAGE)
            .withCommand("sleep", "infinity")
            .withCopyFileToContainer(MountableFile.forHostPath(ScriptHarness.BIN), BIN);

    ExecResult daemon;
    String envMode;
    String javaArgs;
    ExecResult disk;
    String fakeStatMode;
    ExecResult daemonWithFakeStat;
    String envModeAfterFakeStat;

    @BeforeAll
    void runWrappers() throws Exception {
      debian.start();
      prepare(
          "mkdir -p "
              + HOME
              + " && printf 'SCOS_TEST_FROM_ENV_FILE=loaded\\n' > "
              + HOME
              + "/.env && chmod 644 "
              + HOME
              + "/.env"
              + " && printf '#!/bin/bash\\nprintf \"%%s\\\\n\" \"$@\" > "
              + HOME
              + "/java.args\\n' > "
              + HOME
              + "/java && chmod 755 "
              + HOME
              + "/java");
      daemon =
          sh("SCOS_HOME=" + HOME + " SCOS_JAVA=" + HOME + "/java bash " + BIN + "/scos-daemon.sh");
      envMode = sh("stat -c '%a' " + HOME + "/.env").getStdout().strip();
      javaArgs = sh("cat " + HOME + "/java.args").getStdout();
      disk = sh("SCOS_HOME=" + HOME + " SCOS_DISK_PATHS=/ bash " + BIN + "/scos-disk-check.sh");
      runWithFakeStat();
    }

    /**
     * macOS 셸 테스트가 쓰는 가짜 GNU stat(ScriptHarness.GNU_STAT_FAKE)을 GNU에서 그대로 돌린다(F15 T15.0):
     * CI(ubuntu)에서 DaemonWrapperTest$GnuStat이 같은 가짜로 돈다.
     */
    void runWithFakeStat() throws Exception {
      debian.copyFileToContainer(
          Transferable.of(("#!/bin/bash\n" + ScriptHarness.GNU_STAT_FAKE).getBytes(UTF_8), 0755),
          FAKE_BIN + "/stat");
      prepare("touch /tmp/mode-640 && chmod 640 /tmp/mode-640");
      fakeStatMode = sh(FAKE_BIN + "/stat -c '%a' /tmp/mode-640").getStdout().strip();
      prepare(
          "mkdir -p "
              + HOME_FAKE_STAT
              + " && printf 'SCOS_TEST_FROM_ENV_FILE=loaded\\n' > "
              + HOME_FAKE_STAT
              + "/.env && chmod 644 "
              + HOME_FAKE_STAT
              + "/.env");
      daemonWithFakeStat =
          sh(
              "PATH="
                  + FAKE_BIN
                  + ":$PATH SCOS_HOME="
                  + HOME_FAKE_STAT
                  + " SCOS_JAVA="
                  + HOME
                  + "/java bash "
                  + BIN
                  + "/scos-daemon.sh");
      envModeAfterFakeStat = sh("stat -c '%a' " + HOME_FAKE_STAT + "/.env").getStdout().strip();
    }

    @AfterAll
    void close() {
      debian.stop();
    }

    ExecResult sh(String command) throws Exception {
      return debian.execInContainer("bash", "-c", command);
    }

    /** 준비 명령 — 실패하면 그 자리에서 멈춘다. */
    void prepare(String command) throws Exception {
      ExecResult result = sh(command);
      if (result.getExitCode() != 0)
        throw new IllegalStateException(
            "given 준비 실패: " + command + " → " + result.getExitCode() + " " + result.getStderr());
    }

    @Test
    @DisplayName("데몬 래퍼가 종료 코드 0으로 끝난다(.env를 받아 java를 띄운다)")
    void daemonSucceeds() {
      // then
      assertThat(daemon.getExitCode()).as(daemon.getStderr()).isZero();
    }

    @Test
    @DisplayName("644였던 .env를 600으로 고친다")
    void tightensEnvFile() {
      // then
      assertThat(envMode).isEqualTo("600");
    }

    @Test
    @DisplayName("java에 daemon 명령을 넘긴다")
    void launchesDaemon() {
      // then
      assertThat(javaArgs.lines()).contains("daemon");
    }

    @Test
    @DisplayName("디스크 감시가 GNU df -P 출력에서 사용률을 읽는다")
    void diskCheckReadsUsage() {
      // then
      assertThat(disk.getStderr()).containsPattern("\"usedPercent\":[0-9]+");
    }

    @Test
    @DisplayName("가짜 GNU stat은 GNU에서도 -c '%a'로 권한 640을 낸다")
    void fakeStatReportsModeOnGnu() {
      // then
      assertThat(fakeStatMode).isEqualTo("640");
    }

    @Test
    @DisplayName("가짜 GNU stat을 앞에 둔 데몬 래퍼가 종료 코드 0으로 끝난다")
    void daemonSucceedsWithFakeStat() {
      // then
      assertThat(daemonWithFakeStat.getExitCode()).as(daemonWithFakeStat.getStderr()).isZero();
    }

    @Test
    @DisplayName("가짜 GNU stat을 앞에 둔 데몬 래퍼가 644였던 .env를 600으로 고친다")
    void tightensWithFakeStat() {
      // then
      assertThat(envModeAfterFakeStat).isEqualTo("600");
    }
  }
}
