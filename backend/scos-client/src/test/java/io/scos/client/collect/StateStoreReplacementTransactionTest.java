package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("실패한 페이지는 기존 원본의 수정과 삭제를 되돌린다(E5)")
class StateStoreReplacementTransactionTest {
  private StateStoreScenario scenario;

  @BeforeEach
  void givenRejectedReplacementPage(@TempDir Path root) throws Exception {
    scenario = new StateStoreScenario(root);
    scenario.givenRejectedReplacementPageWrite();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("체크포인트 저장이 실패하면 덮어쓴 원본의 이전 내용이 남는다")
  void restoresReplacedContent() {
    // when
    Throwable failure = catchThrowable(scenario::commitReplacementPage);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("synthetic checkpoint failure");
    assertThat(scenario.content("d/a.md")).contains("{\"text\":\"합성 메모\"}");
  }

  @Test
  @DisplayName("체크포인트 저장이 실패하면 삭제한 원본의 이전 내용이 남는다")
  void restoresDeletedContent() {
    // when
    Throwable failure = catchThrowable(scenario::commitReplacementPage);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("synthetic checkpoint failure");
    assertThat(scenario.content("d/b.md")).contains("{\"text\":\"삭제 전 메모\"}");
  }
}
