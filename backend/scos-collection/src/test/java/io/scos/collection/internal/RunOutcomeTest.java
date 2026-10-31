package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("run 결과는 끝난 상태에서 오류 수와 표시를 정한다")
class RunOutcomeTest {
  static final String KIND = "rate_limited";
  static final String SUMMARY = "429";

  @Test
  @DisplayName("거부 없이 끝나면 오류 없는 succeeded다")
  void succeededWithoutRejections() {
    // when
    RunOutcome outcome = RunOutcome.succeeded(0);

    // then
    assertThat(outcome).isEqualTo(RunOutcome.clean(RunStatus.SUCCEEDED));
  }

  @Test
  @DisplayName("거부가 있으면 오류 없는 succeeded_with_rejections다")
  void succeededWithRejections() {
    // when
    RunOutcome outcome = RunOutcome.succeeded(1);

    // then
    assertThat(outcome).isEqualTo(RunOutcome.clean(RunStatus.SUCCEEDED_WITH_REJECTIONS));
  }

  @Test
  @DisplayName("취소로 멈추면 오류를 세지 않는 cancelled다")
  void cancelledCountsNoError() {
    // when
    RunOutcome outcome = RunOutcome.interrupted(true, 3, KIND, SUMMARY);

    // then
    assertThat(outcome).isEqualTo(RunOutcome.clean(RunStatus.CANCELLED));
  }

  @Test
  @DisplayName("페이지를 반영한 뒤 던지면 오류 하나의 partially_succeeded다")
  void partialAfterPages() {
    // when
    RunOutcome outcome = RunOutcome.interrupted(false, 1, KIND, SUMMARY);

    // then
    assertThat(outcome).isEqualTo(RunOutcome.failed(RunStatus.PARTIALLY_SUCCEEDED, KIND, SUMMARY));
  }

  @Test
  @DisplayName("페이지 없이 던지면 오류 하나의 failed다")
  void failedWithoutPages() {
    // when
    RunOutcome outcome = RunOutcome.interrupted(false, 0, KIND, SUMMARY);

    // then
    assertThat(outcome)
        .extracting(RunOutcome::status, RunOutcome::errorCount, RunOutcome::errorKind)
        .containsExactly(RunStatus.FAILED, 1, Optional.of(KIND));
  }

  @Test
  @DisplayName("오류 요약은 500자로 잘린다")
  void truncatesErrorSummary() {
    // when
    RunOutcome outcome = RunOutcome.failed(RunStatus.FAILED, KIND, "e".repeat(600));

    // then
    assertThat(outcome.errorSummary().orElseThrow()).hasSize(RunOutcome.SUMMARY_CHARS);
  }

  @Test
  @DisplayName("running 상태로는 끝낼 수 없다")
  void rejectsRunningStatus() {
    // when·then
    assertThatThrownBy(() -> RunOutcome.clean(RunStatus.RUNNING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("표시를 붙여도 상태와 오류 수는 그대로다")
  void markKeepsStatusAndCount() {
    // when
    RunOutcome marked = RunOutcome.succeeded(0).markedAs("snapshot_shrink_guarded", "skipped");

    // then
    assertThat(marked)
        .extracting(RunOutcome::status, RunOutcome::errorCount, RunOutcome::errorKind)
        .containsExactly(RunStatus.SUCCEEDED, 0, Optional.of("snapshot_shrink_guarded"));
  }
}
