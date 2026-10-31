package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * `reembed --space=<id> [--dry-run]`(stage2 §3.3): 공간의 저장 세대가 설정 임베더와 다르면 모든 유닛을 다시 임베딩하고 결과 한 줄을
 * 로그에 쓴다. --dry-run은 계획 한 줄만 쓰고 아무것도 바꾸지 않는다. 실제 조립(ScosApplication) 위에서 테스트 임베더로 돈다.
 */
@Tag("db")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("reembed는 공간을 설정 임베더의 세대로 다시 임베딩한다")
class ReembedCommandTest {
  static final String OLD_GENERATION = "gemini:gemini-embedding-001:768:title+aliases+content";
  static final double[] OLD_VECTOR = {0, 0, 0, 1};
  static final int UNITS = 3;

  final CommandScenario commands = new CommandScenario();

  @AfterEach
  void close() {
    commands.close();
  }

  @Test
  @DisplayName("reembed는 세대가 다른 공간을 다시 임베딩하고 결과 줄을 쓴다")
  void reembedsAndReports(CapturedOutput output) {
    // given
    commands.givenEmbeddedSpace(UNITS, OLD_GENERATION, OLD_VECTOR);

    // when
    commands.run("reembed", "--space=legacy");

    // then
    assertThat(commands.storedGeneration()).contains(CommandScenario.GENERATION);
    assertThat(output.getErr())
        .contains(
            "reembed done: space=legacy units=3 fromCache=0 embedded=3 generation="
                + CommandScenario.GENERATION);
  }

  @Test
  @DisplayName("reembed --dry-run은 쓰지 않는다")
  void dryRunWritesNothing() {
    // given
    commands.givenEmbeddedSpace(UNITS, OLD_GENERATION, OLD_VECTOR);

    // when
    commands.run("reembed", "--space=legacy", "--dry-run");

    // then
    assertThat(commands.storedGeneration()).contains(OLD_GENERATION);
    assertThat(commands.vectors()).hasSize(UNITS).containsOnly(OLD_VECTOR);
  }

  @Test
  @DisplayName("reembed --dry-run은 유닛 수·저장 세대·목표 세대를 한 줄로 쓴다")
  void dryRunReportsPlan(CapturedOutput output) {
    // given
    commands.givenEmbeddedSpace(UNITS, OLD_GENERATION, OLD_VECTOR);

    // when
    commands.run("reembed", "--space=legacy", "--dry-run");

    // then
    assertThat(output.getErr())
        .contains(
            "reembed plan: space=legacy units=3 stored="
                + OLD_GENERATION
                + " target="
                + CommandScenario.GENERATION);
  }

  @Test
  @DisplayName("reembed는 유닛도 세대도 없는 공간이면 쓰지 않고 빈 공간이라고 한 줄로 쓴다")
  void emptySpaceReportsSkip(CapturedOutput output) {
    // when: 잘못 적은 공간 id
    commands.run("reembed", "--space=legcy");

    // then
    assertThat(output.getErr())
        .contains(
            "reembed skipped: space=legcy reason=empty-space generation="
                + CommandScenario.GENERATION);
  }
}
