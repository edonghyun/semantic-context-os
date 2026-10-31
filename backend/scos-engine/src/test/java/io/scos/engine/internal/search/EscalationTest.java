package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.Effort;
import io.scos.engine.internal.search.plan.PlanResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("자동 상향은 LOW에서 결과가 모자랄 때 MEDIUM으로 한 번만 한다")
class EscalationTest {
  final Escalation escalation = new Escalation(new PlanResolver());

  @Test
  @DisplayName("LOW에서 요청한 개수보다 적으면 MEDIUM이다")
  void lowWithFewHitsEscalates() {
    // when·then
    assertThat(escalation.next(Effort.LOW, 2, 5)).contains(Effort.MEDIUM);
  }

  @Test
  @DisplayName("LOW라도 개수가 차면 상향하지 않는다")
  void lowWithEnoughHitsStays() {
    // when·then
    assertThat(escalation.next(Effort.LOW, 5, 5)).isEmpty();
  }

  @Test
  @DisplayName("MEDIUM·HIGH는 결과가 없어도 상향하지 않는다")
  void higherEffortsNeverEscalate() {
    // when·then
    assertThat(escalation.next(Effort.MEDIUM, 0, 5)).isEmpty();
    assertThat(escalation.next(Effort.HIGH, 0, 5)).isEmpty();
  }
}
