package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import io.scos.engine.EventLog;
import io.scos.spi.source.SourceInstanceId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * logback-spring.xml이 프로필마다 어디에 어떤 모양으로 쓰는지(logging.md §1). Spring Boot LoggingSystem으로 이 설정을 실제로
 * 올리고 로그를 남겨 출력을 읽는다. 색 여부는 Spring Boot `spring.output.ansi.enabled`(AnsiOutput)를 따른다 — 여기서는
 * AnsiOutput을 직접 정한다.
 */
@Isolated("logback 설정과 AnsiOutput은 JVM 전역이다")
@DisplayName("logback 설정은 프로필마다 정한 곳에 정한 모양으로 쓴다")
class LogbackOutputTest {
  static final String ESC = "\u001B[";
  // 오프셋은 Spring Boot 기본(XXX)이라 UTC에서는 Z로 찍힌다
  static final String TIMESTAMP =
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})";

  final Logger log = LoggerFactory.getLogger(LogbackOutputTest.class);
  final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  /** 프로필·설정값으로 logback-spring.xml을 올리고, stderr로 가는 콘솔 appender의 출력을 가로챈다. */
  void configure(Map<String, Object> properties, String... profiles) {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("spring.application.name", "scos")));
    env.getPropertySources().addFirst(new MapPropertySource("given", properties));
    env.setActiveProfiles(profiles);
    LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
    // 이미 올린 LoggerContext는 다시 올리지 않으므로 먼저 치운다
    system.cleanUp();
    system.beforeInitialize();
    system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    for (Appender<ILoggingEvent> appender : rootAppenders())
      if (appender instanceof ConsoleAppender<ILoggingEvent> console
          && console.getTarget().equals("System.err")) console.setOutputStream(stderr);
  }

  static List<Appender<ILoggingEvent>> rootAppenders() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    List<Appender<ILoggingEvent>> out = new ArrayList<>();
    context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().forEachRemaining(out::add);
    return out;
  }

  /** 루트에 붙은 콘솔 appender의 대상(System.out·System.err). */
  static List<String> consoleTargets() {
    return rootAppenders().stream()
        .filter(appender -> appender instanceof ConsoleAppender)
        .map(appender -> ((ConsoleAppender<ILoggingEvent>) appender).getTarget())
        .toList();
  }

  /** 스레드 이름이 줄에 실리므로 이름을 정한 스레드에서 남긴다. */
  static void onThread(String name, Runnable logging) {
    Thread thread = new Thread(logging, name);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  String stderr() {
    return stderr.toString(StandardCharsets.UTF_8);
  }

  @AfterEach
  void restore() {
    AnsiOutput.setEnabled(AnsiOutput.Enabled.DETECT);
    configure(Map.of());
  }

  @Nested
  @DisplayName("명령 프로필(프로필 없음)에서")
  class Command {
    @Test
    @DisplayName("stderr 줄은 Spring Boot 기본 콘솔 모양이다")
    void writesSpringBootConsoleShape() {
      // given
      AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER);
      configure(Map.of());

      // when
      onThread("collector-2", () -> log.info("hello"));

      // then
      assertThat(stderr())
          .matches(
              TIMESTAMP
                  + "  INFO \\d+ --- \\[scos\\] \\[    collector-2\\] "
                  + "io\\.scos\\.server\\.LogbackOutputTest {9}: hello\\R");
    }

    @Test
    @DisplayName("색이 켜지면(터미널 감지) 수준·로거에 Spring Boot 기본 색을 입힌다")
    void colorsWhenAnsiEnabled() {
      // given: detect가 터미널을 찾은 것과 같다 — detect 결과는 JVM마다 한 번 정해져 직접 켠다
      AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
      configure(Map.of());

      // when
      onThread("collector-2", () -> log.warn("careful"));

      // then
      assertThat(stderr())
          .contains(ESC + "33m WARN" + ESC + "0;39m")
          .contains(ESC + "36mio.scos.server.LogbackOutputTest        " + ESC + "0;39m");
    }

    @Test
    @DisplayName("원인 예외는 메시지 줄 아래에 스택으로 쓴다")
    void writesStackBelowMessage() {
      // given
      AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER);
      configure(Map.of());

      // when
      onThread("collector-2", () -> log.error("run failed", new IllegalStateException("boom")));

      // then
      assertThat(stderr())
          .containsPattern(
              ": run failed\\R(\\R)?java\\.lang\\.IllegalStateException: boom\\R\\tat io\\.scos\\.");
    }
  }

  @Nested
  @DisplayName("daemon 프로필에서")
  class Daemon {
    static final String RUN = "8dff121d";

    @TempDir Path dir;

    /** 실패 이벤트 하나를 원인 예외와 함께 남긴다. 색은 daemon 프로필 설정(always)과 같게 켠다. */
    void logRunFailed() {
      AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
      configure(Map.of("scos.log.dir", dir.toString()), "daemon");
      onThread(
          "collector-2",
          () ->
              EventLog.error(log, "collection.run.failed")
                  .source(new SourceInstanceId("team-notion"))
                  .run(RUN)
                  .detail("errorKind", "invalid_provider_response")
                  .cause(new IllegalStateException("boom"))
                  .log());
    }

    String serveLog() throws IOException {
      return Files.readString(dir.resolve("serve.log"));
    }

    @Test
    @DisplayName("serve.log 이벤트 줄은 Spring Boot 모양에 이벤트 메시지다")
    void serveLogHasEventLine() throws IOException {
      // when
      logRunFailed();

      // then
      assertThat(serveLog().replaceAll("\u001B\\[[0-9;]*m", "").lines().findFirst())
          .hasValueSatisfying(
              line ->
                  assertThat(line)
                      .matches(
                          TIMESTAMP
                              + " ERROR \\d+ --- \\[scos\\] \\[    collector-2\\] "
                              + "io\\.scos\\.server\\.LogbackOutputTest {9}: "
                              + "collection\\.run\\.failed source=team-notion run=8dff121d "
                              + "errorKind=invalid_provider_response"));
    }

    @Test
    @DisplayName("serve.log는 터미널이 아니어도 색 코드를 쓴다")
    void serveLogIsColored() throws IOException {
      // when
      logRunFailed();

      // then
      assertThat(serveLog()).contains(ESC + "31mERROR" + ESC + "0;39m");
    }

    @Test
    @DisplayName("serve.log는 원인 예외의 스택을 이벤트 줄 아래에 쓴다")
    void serveLogHasStack() throws IOException {
      // when
      logRunFailed();

      // then
      assertThat(serveLog())
          .containsPattern("\\Rjava\\.lang\\.IllegalStateException: boom\\R\\tat ");
    }

    @Test
    @DisplayName("serve.jsonl 줄은 ADR-0023 JSON 줄 모양이다")
    void serveJsonlHasJsonLine() throws IOException {
      // when
      logRunFailed();

      // then: ts·level·event·sourceInstanceId·runId·detail·error 순서
      JsonNode line = JsonMapper.shared().readTree(Files.readString(dir.resolve("serve.jsonl")));
      assertThat(line.propertyNames())
          .containsExactly("ts", "level", "event", "sourceInstanceId", "runId", "detail", "error");
    }

    @Test
    @DisplayName("stdout·stderr에는 쓰지 않는다(launchd가 넘기는 파일은 기동 전 출력만 받는다)")
    void writesNoConsole() {
      // when
      logRunFailed();

      // then
      assertThat(consoleTargets()).isEmpty();
    }

    @Test
    @DisplayName("두 파일 모두 하루 단위로 회전하고 14일 보관한다")
    void rotatesDailyKeepingFourteenDays() {
      // when
      logRunFailed();

      // then
      assertThat(rollingPolicies())
          .extracting(
              TimeBasedRollingPolicy::getFileNamePattern, TimeBasedRollingPolicy::getMaxHistory)
          .containsExactlyInAnyOrder(
              tuple(dir + "/serve.%d{yyyy-MM-dd}.log", 14),
              tuple(dir + "/serve.%d{yyyy-MM-dd}.jsonl", 14));
    }

    static List<TimeBasedRollingPolicy<?>> rollingPolicies() {
      List<TimeBasedRollingPolicy<?>> out = new ArrayList<>();
      for (Appender<ILoggingEvent> appender : rootAppenders())
        if (appender instanceof RollingFileAppender<?> file
            && file.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) out.add(policy);
      return out;
    }
  }
}
