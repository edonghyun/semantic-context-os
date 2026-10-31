package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("수집 런타임은 소스에 한정한 완료 요약을 어댑터에 준다")
public abstract class BodyRuntimeStateContract {
  protected abstract BodyRuntimeScenario newScenario();

  @Test
  @DisplayName("실제 저장 완료 요약이 수집 입력으로 전달된다")
  void providesStoredSummary() {
    // given
    var context = newScenario();
    // when
    var summaries = context.collect();
    // then
    assertThat(summaries).containsKey(BodyCommitScenario.PAGE);
    assertThat(summaries.get(BodyCommitScenario.PAGE).isCompleted("v1")).isTrue();
  }
}
