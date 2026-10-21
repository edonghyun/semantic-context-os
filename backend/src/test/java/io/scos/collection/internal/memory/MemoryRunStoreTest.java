package io.scos.collection.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 메모리 run 저장소의 준비 어휘 {@link MemoryRunStore#givenFinished} 자체 테스트(F15 C7). 포트 계약은
 * MemoryContractTests가 본다.
 */
@DisplayName("메모리 run 저장소의 givenFinished는 전제 밖 상태에서 멈춘다(C7)")
class MemoryRunStoreTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final SourceInstanceId OTHER = new SourceInstanceId("src-b");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Instant T1 = Instant.parse("2026-10-16T00:01:00Z");

  final MemoryRunStore store = new MemoryRunStore();

  @Nested
  @DisplayName("같은 소스에 running run이 있으면")
  class RunningRunOnSameSource {
    @BeforeEach
    void givenRunning() {
      store.start(new RunId("running"), SOURCE, T0);
    }

    @Test
    @DisplayName("끝난 run을 더하려 하면 given 준비 실패로 멈춘다")
    void stops() {
      // when·then
      assertThatThrownBy(
              () -> store.givenFinished(new RunId("done"), SOURCE, RunStatus.SUCCEEDED, T0, T1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("given 준비 실패: 소스 src-a에 running run running이 있다");
    }

    @Test
    @DisplayName("대조군: 다른 소스에 끝난 run을 더하면 이 run은 running으로 남는다")
    void otherSourceLeavesRunning() {
      // when
      store.givenFinished(new RunId("done"), OTHER, RunStatus.SUCCEEDED, T0, T1);

      // then
      assertThat(store.get(new RunId("running")).orElseThrow().status())
          .isEqualTo(RunStatus.RUNNING);
    }
  }

  @Test
  @DisplayName("이미 있는 run id로 더하려 하면 given 준비 실패로 멈춘다")
  void stopsOnDuplicateId() {
    // given
    store.givenFinished(new RunId("done"), SOURCE, RunStatus.SUCCEEDED, T0, T1);

    // when·then
    assertThatThrownBy(
            () -> store.givenFinished(new RunId("done"), SOURCE, RunStatus.FAILED, T0, T1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: run done이 이미 있다");
  }

  @Test
  @DisplayName("실패로 더한 run은 오류 1건, 완료 시각 T1로 남는다")
  void failedRunHasOneError() {
    // when
    var run = store.givenFinished(new RunId("done"), SOURCE, RunStatus.FAILED, T0, T1);

    // then
    assertThat(run)
        .extracting(r -> r.status(), r -> r.errorCount(), r -> r.finishedAt())
        .containsExactly(RunStatus.FAILED, 1, Optional.of(T1));
  }
}
