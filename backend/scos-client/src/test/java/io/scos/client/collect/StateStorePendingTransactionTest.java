package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("투영 대기의 일괄 변경은 부분 실패를 롤백한다(CG-6)")
class StateStorePendingTransactionTest {
  private StateStoreScenario scenario;

  @BeforeEach
  void setUp(@TempDir Path root) {
    scenario = new StateStoreScenario(root);
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("두 번째 원천 대기 등록이 실패하면 첫 번째 원천 등록도 되돌린다")
  void rollsBackMarkAll() throws Exception {
    // given
    scenario.givenRejectedPendingMark();
    // when
    Throwable failure = catchThrowable(scenario::markAllPending);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("synthetic mark failure");
    assertThat(scenario.pendingProjection()).isEmpty();
  }

  @Test
  @DisplayName("두 번째 문서 대기 삭제가 실패하면 첫 번째 대기도 남는다")
  void rollsBackClear() throws Exception {
    // given
    scenario.givenRejectedPendingClear();
    // when
    Throwable failure = catchThrowable(scenario::clearPending);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("synthetic clear failure");
    assertThat(scenario.pendingProjection()).containsExactly("memory/d/a.md", "memory/d/b.md");
  }
}
