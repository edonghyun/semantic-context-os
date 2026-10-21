package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 데몬 래퍼(deploy/java/bin/scos-daemon.sh)의 계약 — TS serve.sh와 같다: .env 권한 600, 정비 유예 7일. Java만의 계약: 힙
 * 상한 변수, JVM 옵션 변수, 종료 코드 해석(143은 정상 정지 0, 70은 그대로 70 — launchd가 다시 띄운다), SIGTERM·SIGINT 전달. JVM은
 * 가짜 java다.
 */
@Tag("process")
@DisplayName("데몬 래퍼 scos-daemon.sh는 TS serve.sh와 같게 JVM을 감독한다")
class DaemonWrapperTest {
  static final String SCRIPT = "scos-daemon.sh";
  static final int LOOP_DIED = 70;
  static final int STARTUP_FAILED = 1;

  /** -XX:+ExitOnOutOfMemoryError의 JVM 종료 코드. */
  static final int OOM_EXIT = 3;

  static final ObjectMapper JSON = new ObjectMapper();

  final ScriptHarness wrapper = new ScriptHarness().fakeJava();

  @AfterEach
  void close() {
    wrapper.close();
  }

  List<String> javaArgs() {
    return wrapper.read(wrapper.records.resolve("java.args")).lines().toList();
  }

  @Nested
  @DisplayName(".env 권한이 600이 아니면")
  class LooseEnvFile {
    @BeforeEach
    void givenLooseEnvFile() {
      wrapper.withEnvFile("rw-r--r--");
    }

    @Test
    @DisplayName("600으로 고친다")
    void tightensTo600() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(wrapper.home.resolve(".env"))).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("고쳤다는 경고를 남긴다")
    void warns() {
      // when
      var result = wrapper.run(SCRIPT);

      // then
      assertThat(result.stderr()).contains("\"event\":\"wrapper.env.permissions.fixed\"");
    }
  }

  @Nested
  @DisplayName("GNU stat이 있는 호스트에서 실행하면")
  class GnuStat {
    @BeforeEach
    void givenGnuStat() {
      wrapper.gnuStat();
    }

    @Test
    @DisplayName("권한 600인 .env를 받아 java를 띄운다")
    void acceptsEnvFile() {
      // given
      wrapper.withEnvFile();

      // when
      var result = wrapper.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isZero();
    }

    @Test
    @DisplayName("권한이 600이 아니면 고친다")
    void tightens() {
      // given
      wrapper.withEnvFile("rw-r--r--");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(wrapper.home.resolve(".env"))).isEqualTo("rw-------");
    }
  }

  @Nested
  @DisplayName(".env가 없으면")
  class MissingEnvFile {
    @Test
    @DisplayName("종료 코드 1로 끝난다")
    void exitsOne() {
      // when
      var result = wrapper.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isEqualTo(STARTUP_FAILED);
    }

    @Test
    @DisplayName("java를 띄우지 않는다")
    void launchesNoJava() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.records.resolve("java.args")).doesNotExist();
    }

    @Test
    @DisplayName("따옴표가 든 경로도 올바른 JSON 로그로 남긴다")
    void logsValidJson() throws Exception {
      // given
      String path = wrapper.home.resolve("say \"hi\"\t.env").toString();
      wrapper.env.put("SCOS_ENV_FILE", path);

      // when
      var result = wrapper.run(SCRIPT);

      // then
      JsonNode line =
          JSON.readTree(
              result
                  .stderr()
                  .lines()
                  .filter(l -> l.contains("\"event\":\"wrapper.env.missing\""))
                  .findFirst()
                  .orElseThrow());
      assertThat(line.path("detail").path("path").asText()).isEqualTo(path);
    }
  }

  @Nested
  @DisplayName("권한 600 .env로 실행하면")
  class WithEnvFile {
    @BeforeEach
    void givenEnvFile() {
      wrapper.withEnvFile();
    }

    @Test
    @DisplayName(".env 값을 java 환경에 넘긴다")
    void exportsEnvFile() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.env")))
          .contains("SCOS_TEST_FROM_ENV_FILE=loaded");
    }

    @Test
    @DisplayName("정비 유예는 기본 7일이다(TS serve.sh --maintenance-grace-days 7)")
    void graceDaysDefaultSeven() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.env")))
          .contains("SCOS_DAEMON_MAINTENANCE_GRACE_DAYS=7");
    }

    @Test
    @DisplayName("java -jar <JAR> daemon으로 띄운다")
    void launchesDaemonCommand() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).containsSubsequence("-jar", wrapper.env.get("SCOS_JAR"), "daemon");
    }

    @Test
    @DisplayName("OOM이면 JVM을 끝내도록 -XX:+ExitOnOutOfMemoryError로 띄운다")
    void exitsOnOutOfMemory() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).contains("-XX:+ExitOnOutOfMemoryError");
    }

    @Test
    @DisplayName("힙 상한은 기본 2g이다")
    void heapDefault() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).contains("-Xmx2g");
    }

    @Test
    @DisplayName("힙 상한은 SCOS_DAEMON_HEAP을 따른다")
    void heapFromVariable() {
      // given
      wrapper.env.put("SCOS_DAEMON_HEAP", "3g");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).contains("-Xmx3g");
    }

    @Test
    @DisplayName("SCOS_DAEMON_HEAP을 비우면 -Xmx를 넣지 않는다(컨테이너 비율 상한용)")
    void heapOmittedWhenEmpty() {
      // given
      wrapper.env.put("SCOS_DAEMON_HEAP", "");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).noneMatch(arg -> arg.startsWith("-Xmx"));
    }

    @Test
    @DisplayName("SCOS_JAVA_OPTS를 공백으로 나눠 JVM 옵션으로 넘긴다")
    void splitsOnSpaces() {
      // given
      wrapper.env.put("SCOS_JAVA_OPTS", "-Da=1 -XX:MaxRAMPercentage=50");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).containsSubsequence("-Da=1", "-XX:MaxRAMPercentage=50", "-jar");
    }

    @Test
    @DisplayName("SCOS_JAVA_OPTS의 글롭 문자를 파일 이름으로 펼치지 않는다")
    void doesNotGlob() throws Exception {
      // given: 글롭이 펼쳐지면 맞을 파일이 있다
      Files.writeString(wrapper.home.resolve("-Dx=expanded"), "");
      wrapper.env.put("SCOS_JAVA_OPTS", "-Dx=*");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(javaArgs()).contains("-Dx=*");
    }

    @Test
    @DisplayName("java의 기동 실패(1)는 그대로 1로 끝난다")
    void startupFailurePassesThrough() {
      // given
      wrapper.env.put("FAKE_JAVA_EXIT", String.valueOf(STARTUP_FAILED));

      // when
      var result = wrapper.run(SCRIPT);

      // then
      assertThat(result.exitCode()).isEqualTo(STARTUP_FAILED);
    }

    @Nested
    @DisplayName("데몬 루프가 죽어 java가 70으로 끝나면")
    class LoopDied {
      @BeforeEach
      void givenLoopDies() {
        wrapper.env.put("FAKE_JAVA_EXIT", String.valueOf(LOOP_DIED));
      }

      @Test
      @DisplayName("래퍼도 70으로 끝난다(감시자가 다시 띄운다)")
      void exitsSeventy() {
        // when
        var result = wrapper.run(SCRIPT);

        // then
        assertThat(result.exitCode()).isEqualTo(LOOP_DIED);
      }

      @Test
      @DisplayName("error daemon.loop.died를 남긴다")
      void logsLoopDied() {
        // when
        var result = wrapper.run(SCRIPT);

        // then
        assertThat(result.stderr()).contains("\"level\":\"error\",\"event\":\"daemon.loop.died\"");
      }
    }

    @Nested
    @DisplayName("OOM으로 java가 3으로 끝나면")
    class OutOfMemory {
      @BeforeEach
      void givenOutOfMemory() {
        wrapper.env.put("FAKE_JAVA_EXIT", String.valueOf(OOM_EXIT));
      }

      @Test
      @DisplayName("래퍼도 3으로 끝난다(감시자가 다시 띄운다)")
      void exitsThree() {
        // when
        var result = wrapper.run(SCRIPT);

        // then
        assertThat(result.exitCode()).isEqualTo(OOM_EXIT);
      }

      @Test
      @DisplayName("error daemon.out_of_memory를 남긴다")
      void logsOutOfMemory() {
        // when
        var result = wrapper.run(SCRIPT);

        // then
        assertThat(result.stderr())
            .contains("\"level\":\"error\",\"event\":\"daemon.out_of_memory\"");
      }
    }

    @Nested
    @DisplayName("java가 뜬 뒤 래퍼가 신호를 받으면")
    class Signalled {
      @BeforeEach
      void givenWaitingJava() {
        wrapper.env.put("FAKE_JAVA_WAIT", "1");
      }

      /** 래퍼를 띄우고 java가 뜬 뒤 신호를 보내 끝난 종료 코드를 돌려준다. */
      int signal(String name) throws InterruptedException {
        Process p = wrapper.start(SCRIPT);
        wrapper.awaitFile(wrapper.records.resolve("java.started"));
        ScriptHarness.signal(p, name);
        if (!p.waitFor(ScriptHarness.PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
          throw new IllegalStateException("given 준비 실패: 래퍼가 끝나지 않았다");
        return p.exitValue();
      }

      @Test
      @DisplayName("SIGTERM을 java에 넘긴다")
      void forwardsTerm() throws InterruptedException {
        // when
        signal("TERM");

        // then
        assertThat(wrapper.records.resolve("java.term")).exists();
      }

      @Test
      @DisplayName("SIGINT도 java에 SIGTERM으로 넘긴다")
      void forwardsInt() throws InterruptedException {
        // when
        signal("INT");

        // then
        assertThat(wrapper.records.resolve("java.term")).exists();
      }

      @Test
      @DisplayName("java의 143을 정상 정지 0으로 끝낸다")
      void exitsZero() throws InterruptedException {
        // when
        int exitCode = signal("TERM");

        // then
        assertThat(exitCode).isZero();
      }
    }
  }
}
