package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@DisplayName("로그 관찰은 추가 기록과 겹쳐도 읽기 시점의 스냅숏을 돌려준다")
class LogCaptureTest {
  @Test
  @DisplayName("인코딩 중 새 로그가 생겨도 관찰하던 목록을 바꾸지 않는다")
  void readsSnapshotWhileAnotherEventIsAppended() {
    try (var scenario = new LogCaptureScenario()) {
      scenario.givenAppendDuringFormatting();
      // when
      List<String> messages = scenario.messages();
      // then
      assertThat(messages).containsExactly("first");
    }
  }
}
