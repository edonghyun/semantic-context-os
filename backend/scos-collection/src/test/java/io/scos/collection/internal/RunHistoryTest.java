package io.scos.collection.internal;

import static io.scos.collection.internal.RunStatus.CANCELLED;
import static io.scos.collection.internal.RunStatus.FAILED;
import static io.scos.collection.internal.RunStatus.PARTIALLY_SUCCEEDED;
import static io.scos.collection.internal.RunStatus.RUNNING;
import static io.scos.collection.internal.RunStatus.SUCCEEDED;
import static io.scos.collection.internal.RunStatus.SUCCEEDED_WITH_REJECTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("run 이력은 마지막 성공 뒤의 실패만 센다")
class RunHistoryTest {
  @Test
  @DisplayName("최근 run부터 세다가 첫 성공에서 멈춘다")
  void stopsAtLastSuccess() {
    // given
    var history =
        new RunHistory(List.of(FAILED, PARTIALLY_SUCCEEDED, SUCCEEDED_WITH_REJECTIONS, FAILED));

    // when
    int failures = history.failuresSinceLastSuccess();

    // then
    assertThat(failures).isEqualTo(2);
  }

  @Test
  @DisplayName("실행 중이거나 취소된 run은 실패로 세지 않는다")
  void runningAndCancelledAreNotFailures() {
    // given
    var history = new RunHistory(List.of(RUNNING, FAILED, CANCELLED, SUCCEEDED));

    // when
    int failures = history.failuresSinceLastSuccess();

    // then
    assertThat(failures).isEqualTo(1);
  }
}
