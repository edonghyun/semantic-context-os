package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("외부 명령은 종료 코드에 따라 출력과 실패를 구분한다(C10)")
class ExternalCommandTest {
  @Test
  @DisplayName("명령이 성공하면 UTF-8 표준 출력을 그대로 돌려준다")
  void returnsSuccessfulOutput() {
    // given
    var command = new ExternalCommand("sh", Set.of());

    // when
    String output = command.run(List.of("sh", "-c", "printf '한글\\n'; printf error >&2"));

    // then
    assertThat(output).isEqualTo("한글\n");
  }

  @Test
  @DisplayName("매치 없음 종료 코드이면 표준 출력이 있어도 빈 결과를 돌려준다")
  void noMatchDiscardsOutput() {
    // given
    var command = new ExternalCommand("rg", Set.of(1));

    // when
    String output = command.run(List.of("sh", "-c", "printf ignored; exit 1"));

    // then
    assertThat(output).isEmpty();
  }

  @Test
  @DisplayName("허용하지 않은 종료 코드이면 명령 이름과 코드를 보고한다")
  void rejectsUnexpectedExitCode() {
    // given
    var command = new ExternalCommand("rg", Set.of(1));

    // when·then
    assertThatThrownBy(() -> command.run(List.of("sh", "-c", "exit 2")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("rg exited 2");
  }

  @Test
  @DisplayName("git의 종료 코드 1은 빈 결과로 취급하지 않는다")
  void gitRejectsExitOne() {
    // given
    var command = new ExternalCommand("git", Set.of());

    // when·then
    assertThatThrownBy(() -> command.run(List.of("sh", "-c", "exit 1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("git exited 1");
  }

  @Test
  @DisplayName("실행 파일이 없으면 명령 이름과 원인을 보고한다")
  void reportsMissingExecutable() {
    // given
    var command = new ExternalCommand("rg", Set.of(1));

    // when·then
    assertThatThrownBy(() -> command.run(List.of("/no-such-baseline-command")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("rg could not run");
  }
}
