package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("메서드 길이 고정 목록은 새로 길어진 것을 막고 줄어든 것은 갱신을 요구한다")
class MethodLengthBaselineTest {
  static final int LIMIT = 40;

  static final MethodLengthBaseline BASELINE =
      MethodLengthBaseline.parse(
          """
          # 주석 줄
          50\ta/A.java A.long()
          45\ta/A.java A.other()
          """);

  @Test
  @DisplayName("목록 밖에서 상한을 넘는 메서드를 잡는다")
  void catchesNewLongMethod() {
    // when
    List<String> problems =
        BASELINE.problems(
            Map.of("a/A.java A.long()", 50, "a/A.java A.other()", 45, "a/A.java A.fresh()", 41),
            LIMIT);

    // then
    assertThat(problems).singleElement().asString().contains("A.fresh()", "41");
  }

  @Test
  @DisplayName("고정된 줄 수보다 길어진 메서드를 잡는다")
  void catchesGrownMethod() {
    // when
    List<String> problems =
        BASELINE.problems(Map.of("a/A.java A.long()", 51, "a/A.java A.other()", 45), LIMIT);

    // then
    assertThat(problems).singleElement().asString().contains("A.long()", "51", "50");
  }

  @Test
  @DisplayName("줄어들거나 사라진 메서드는 목록 갱신을 요구한다")
  void requiresUpdateWhenShrunkOrGone() {
    // when
    List<String> problems = BASELINE.problems(Map.of("a/A.java A.long()", 48), LIMIT);

    // then
    assertThat(problems)
        .hasSize(2)
        .allMatch(p -> p.contains("갱신"))
        .anyMatch(p -> p.contains("A.other()"));
  }

  @Test
  @DisplayName("고정된 그대로이고 새 메서드가 상한 안이면 문제가 없다")
  void passesWithinBaseline() {
    // when
    List<String> problems =
        BASELINE.problems(
            Map.of("a/A.java A.long()", 50, "a/A.java A.other()", 45, "a/A.java A.fresh()", 40),
            LIMIT);

    // then
    assertThat(problems).isEmpty();
  }

  @Test
  @DisplayName("갱신은 줄어든 것만 낮추고 새 것·길어진 것은 넣지 않는다")
  void updateOnlyLowers() {
    // when
    MethodLengthBaseline updated =
        BASELINE.updated(
            Map.of("a/A.java A.long()", 52, "a/A.java A.other()", 30, "a/A.java A.fresh()", 60),
            LIMIT);

    // then
    assertThat(updated.format())
        .endsWith("50\ta/A.java A.long()\n")
        .doesNotContain("A.fresh()", "A.other()");
  }
}
