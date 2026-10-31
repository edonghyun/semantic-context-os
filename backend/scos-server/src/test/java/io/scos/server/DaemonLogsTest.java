package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("데몬 로그는 프로세스 수명 중 회전되어도 기동 기록과 이벤트를 보존한다")
class DaemonLogsTest {
  @TempDir Path dir;
  private Path console;
  private Path directory;
  private DaemonLogs logs;

  @BeforeEach
  void createReader() {
    console = dir.resolve("daemon.console.log");
    directory = dir.resolve("logs");
    logs = new DaemonLogs(console, directory);
  }

  @Nested
  @DisplayName("전날 기동 로그와 오늘 사람 로그가 있으면")
  class HumanRollover {
    @BeforeEach
    void writeLogs() throws IOException {
      Files.createDirectories(directory);
      Files.writeString(
          directory.resolve("serve.2026-10-30.log"),
          "daemon management port=8082 address=127.0.0.1\n");
      Files.writeString(directory.resolve("serve.log"), "daemon cycle completed\n");
      Files.writeString(directory.resolve("serve.stderr.log"), "unrelated stderr\n");
    }

    @Test
    @DisplayName("사람 로그는 회전 파일의 기동 기록부터 시간순으로 반환한다")
    void retainsStartupAcrossMidnight() {
      // when
      String human = logs.human();

      // then
      assertThat(human)
          .isEqualTo("daemon management port=8082 address=127.0.0.1\ndaemon cycle completed\n");
    }
  }

  @Nested
  @DisplayName("기계 로그가 자정에 회전했으면")
  class MachineRollover {
    @BeforeEach
    void writeLogs() throws IOException {
      Files.createDirectories(directory);
      Files.writeString(
          directory.resolve("serve.2026-10-30.jsonl"),
          "{\"ts\":\"2026-10-30T14:59:59Z\",\"level\":\"info\",\"event\":\"daemon.cycle.completed\",\"detail\":{\"attempts\":1}}\n");
      Files.writeString(
          directory.resolve("serve.jsonl"),
          "{\"ts\":\"2026-10-30T15:00:01Z\",\"level\":\"info\",\"event\":\"daemon.cycle.completed\",\"detail\":{\"attempts\":0}}\n");
    }

    @Test
    @DisplayName("이벤트는 전날과 오늘 기록을 시간순으로 반환한다")
    void retainsEventsAcrossMidnight() {
      // when
      var events = logs.events();

      // then
      assertThat(events)
          .extracting(event -> event.path("ts").asString())
          .containsExactly("2026-10-30T14:59:59Z", "2026-10-30T15:00:01Z");
    }
  }

  @Test
  @DisplayName("파일 appender가 뜨기 전 사람 로그는 비어 있다")
  void humanBeforeLoggingIsEmpty() {
    // when
    String human = logs.human();

    // then
    assertThat(human).isEmpty();
  }

  @Nested
  @DisplayName("logback 전 JVM 출력이 있으면")
  class ConsoleBeforeLogging {
    @BeforeEach
    void writeConsole() throws IOException {
      Files.writeString(console, "JVM warning before logback\n");
    }

    @Test
    @DisplayName("콘솔 출력은 그대로 반환한다")
    void retainsConsole() {
      // when
      String output = logs.console();

      // then
      assertThat(output).isEqualTo("JVM warning before logback\n");
    }
  }

  @Nested
  @DisplayName("기계 로그에 JSON 아닌 줄이 있으면")
  class InvalidMachineLine {
    @BeforeEach
    void writeLine() throws IOException {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("serve.jsonl"), "not-json\n");
    }

    @Test
    @DisplayName("이벤트 읽기는 잘못된 줄을 밝히고 실패한다")
    void rejectsNonJson() {
      // when
      Throwable failure = catchThrowable(logs::events);

      // then
      assertThat(failure)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("JSON이 아닌 로그 줄: not-json");
    }
  }
}
