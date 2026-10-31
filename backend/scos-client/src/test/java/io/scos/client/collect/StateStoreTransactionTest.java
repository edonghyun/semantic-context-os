package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.Checkpoint;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("페이지 변경과 체크포인트는 함께 롤백한다(CG-6)")
class StateStoreTransactionTest {
  private StateStoreScenario scenario;

  @BeforeEach
  void givenRejectedCheckpoint(@TempDir Path root) throws Exception {
    scenario = new StateStoreScenario(root);
    scenario.givenRejectedCheckpointWrite();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("체크포인트 저장이 실패하면 앞서 쓴 원본도 남지 않는다")
  void rawItemsRollBack() {
    // when
    Throwable failure = catchThrowable(scenario::commitMemoryPage);

    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("synthetic checkpoint failure");
    assertThat(scenario.documentIds()).isEmpty();
  }

  @Test
  @DisplayName("체크포인트 저장이 실패하면 투영 대기도 남지 않는다")
  void pendingRollsBack() {
    // when
    Throwable failure = catchThrowable(scenario::commitMemoryPage);

    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("synthetic checkpoint failure");
    assertThat(scenario.pendingProjection()).isEmpty();
  }

  @Test
  @DisplayName("체크포인트 저장이 실패하면 이전 체크포인트가 유지된다")
  void checkpointRollsBack() {
    // when
    Throwable failure = catchThrowable(scenario::commitMemoryPage);

    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("synthetic checkpoint failure");
    assertThat(scenario.checkpoint()).contains(new Checkpoint(1, "{\"files\":{}}"));
  }
}
