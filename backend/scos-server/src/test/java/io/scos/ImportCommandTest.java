package io.scos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * `import --file=<units.json> --embedding-generation=<세대>`(stage2 §3.3): 벡터를 동봉한 유닛은 그 벡터를 만든 세대를
 * 밝혀야 하고, 그 세대가 설정 임베더의 세대·차원과 같아야 적재된다. 적재하면 공간 세대를 기록한다. 실제 조립(ScosApplication) 위에서 테스트 임베더(dim
 * 4)로 돈다.
 */
@Tag("db")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("벡터를 동봉한 import는 설정 세대로 만든 벡터만 받는다")
class ImportCommandTest {
  static final double[] VECTOR = {1, 0, 0, 0};
  static final String OTHER_GENERATION = "test:other-model:4:title+aliases+content";

  final CommandScenario commands = new CommandScenario();

  @AfterEach
  void close() {
    commands.close();
  }

  @Test
  @DisplayName("import는 벡터가 있는데 --embedding-generation이 없으면 종료 2다")
  void missingGenerationIsUsageError() {
    // given
    Path file = commands.unitsFile(VECTOR, VECTOR);

    // when
    int exit = commands.run("import", "--file=" + file);

    // then
    assertThat(exit).isEqualTo(2);
  }

  @Test
  @DisplayName("세대 인자 없이 벡터를 import하면 사용법 오류 한 줄을 stderr에 쓴다")
  void missingGenerationNamesTheOption(CapturedOutput output) {
    // given
    Path file = commands.unitsFile(VECTOR, VECTOR);

    // when
    commands.run("import", "--file=" + file);

    // then: 사람이 읽는 CLI 메시지라 로그 줄 모양이 아니라 그 문장 그대로다
    assertThat(output.getErr().lines())
        .contains("import: --embedding-generation is required when units carry embeddings");
  }

  @Test
  @DisplayName("import 세대가 설정과 다르면 거부한다")
  void rejectsOtherGeneration() {
    // given
    Path file = commands.unitsFile(VECTOR, VECTOR);

    // when·then
    assertThatThrownBy(
            () ->
                commands.run(
                    "import", "--file=" + file, "--embedding-generation=" + OTHER_GENERATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "declared embedding generation "
                + OTHER_GENERATION
                + " differs from configured "
                + CommandScenario.GENERATION);
  }

  @Test
  @DisplayName("import 벡터 길이가 설정 차원과 다르면 거부한다")
  void rejectsOtherDimension() {
    // given
    Path file = commands.unitsFile(VECTOR, new double[] {1, 0, 0});

    // when·then
    assertThatThrownBy(
            () ->
                commands.run(
                    "import",
                    "--file=" + file,
                    "--embedding-generation=" + CommandScenario.GENERATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unit u1: embedding has 3 dimensions, configured 4");
  }

  @Test
  @DisplayName("import는 공간 세대를 기록한다")
  void recordsSpaceGeneration() {
    // given
    Path file = commands.unitsFile(VECTOR, VECTOR);

    // when
    commands.run(
        "import", "--file=" + file, "--embedding-generation=" + CommandScenario.GENERATION);

    // then
    assertThat(commands.storedGeneration()).contains(CommandScenario.GENERATION);
  }
}
