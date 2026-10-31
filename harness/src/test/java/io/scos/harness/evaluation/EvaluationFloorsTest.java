package io.scos.harness.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지표 하한: 평균이 하한보다 작을 때만 위반이고, 주지 않은 하한은 판정하지 않는다. */
@DisplayName("평가 하한은 평균이 하한 미만일 때만 위반으로 본다")
class EvaluationFloorsTest {
  @Test
  @DisplayName("평균이 하한보다 작으면 위반이다")
  void belowFloorViolates() {
    // given
    EvaluationFloors floors =
        new EvaluationFloors(Optional.of(0.8), Optional.of(0.5), Optional.of(0.5));

    // when·then
    assertThat(floors.violations(0.79, 0.9, 0.9)).containsExactly("recall");
  }

  @Test
  @DisplayName("하한과 같으면 통과한다")
  void equalToFloorPasses() {
    // given
    EvaluationFloors floors =
        new EvaluationFloors(Optional.of(0.8), Optional.of(0.5), Optional.of(0.6));

    // when·then
    assertThat(floors.violations(0.8, 0.5, 0.6)).isEmpty();
  }

  @Test
  @DisplayName("하한을 주지 않은 지표는 판정하지 않는다")
  void missingFloorIsNotJudged() {
    // given
    EvaluationFloors floors =
        new EvaluationFloors(Optional.empty(), Optional.of(0.5), Optional.empty());

    // when·then
    assertThat(floors.violations(0.0, 0.4, 0.0)).containsExactly("mrr");
  }
}
