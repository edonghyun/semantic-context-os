package io.scos;

import static io.scos.CommandScenario.PUBLIC_CHANNELS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 명령이 남기는 warn·error 이벤트(logging.md §9). 명령은 Spring이 logback을 다시 올려 로거에 단 appender가 떨어진다 — 명령 프로필의
 * stderr(사람 갈래, Spring Boot 형식)에서 이벤트 줄의 수준과 `이름 key=value …` 메시지를 읽는다.
 */
@Tag("db")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("명령은 실패·거부를 이름 붙은 이벤트로 남긴다")
class CommandsEventLogTest {

  final CommandScenario commands = new CommandScenario();

  @AfterEach
  void close() {
    commands.close();
  }

  @Test
  @DisplayName("run이 실패한 collect의 error 줄은 collection.run.failed 하나다")
  void failedCollectRunIsTheOnlyError(CapturedOutput output) {
    // given
    commands.addSource(
        "slack-bad", "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"CMISSING\"]}");

    // when
    commands.run("collect", "--source=slack-bad");

    // then
    assertThat(output.getErr().lines().filter(line -> line.contains(" ERROR ")))
        .singleElement()
        .satisfies(line -> assertThat(line).contains(": collection.run.failed "));
  }

  @Test
  @DisplayName("run이 실패하면 collect는 상태·오류 종류를 info로 남긴다")
  void logsFailedCollect(CapturedOutput output) {
    // given
    commands.addSource(
        "slack-bad", "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"CMISSING\"]}");

    // when
    commands.run("collect", "--source=slack-bad");

    // then
    assertThat(eventLines(output, "command.collect.failed"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains(" INFO ")
                    .endsWith(
                        ": command.collect.failed source=slack-bad status=failed errorKind=invalid_configuration"));
  }

  @Test
  @DisplayName("collect가 run 밖의 예외로 끝나면 error로 메시지를 남긴다")
  void logsThrowingCollect(CapturedOutput output) {
    // given
    commands.givenDriftedSource("a-drifted");

    // when
    commands.run("collect", "--source=a-drifted");

    // then
    assertThat(eventLines(output, "collection.run.failed")).isEmpty();
    assertThat(eventLines(output, "command.collect.failed"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains(" ERROR ")
                    .contains(": command.collect.failed source=a-drifted message="));
  }

  @Test
  @DisplayName("실패한 소스가 모두 run 실패면 cycle은 그 소스들을 info로 남긴다")
  void logsFailedCycle(CapturedOutput output) {
    // given
    commands.addSource(
        "slack-bad", "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"CMISSING\"]}");
    commands.addSource("slack-good", PUBLIC_CHANNELS);

    // when
    commands.run("cycle");

    // then
    assertThat(eventLines(output, "command.cycle.failed"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains(" INFO ")
                    .endsWith(": command.cycle.failed failedSources=[slack-bad]"));
  }

  @Test
  @DisplayName("run 밖의 예외로 실패한 소스가 있으면 cycle은 실패한 소스들을 error로 남긴다")
  void logsThrowingCycle(CapturedOutput output) {
    // given
    commands.givenDriftedSource("a-drifted");
    commands.addSource(
        "slack-bad", "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"CMISSING\"]}");

    // when
    commands.run("cycle");

    // then
    assertThat(eventLines(output, "command.cycle.failed"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains(" ERROR ")
                    .endsWith(": command.cycle.failed failedSources=[a-drifted,slack-bad]"));
  }

  /** stderr에서 이 이벤트의 줄(메시지가 이벤트 이름으로 시작하는 줄). */
  static List<String> eventLines(CapturedOutput output, String event) {
    return output
        .getErr()
        .lines()
        .filter(line -> line.contains(": " + event + " ") || line.endsWith(": " + event))
        .toList();
  }
}
