package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("작업 대기열은 끝낸 앞자리를 앞에 둘 작업 → 남은 작업 → 뒤에 둘 작업으로 바꾼다")
class WorkQueueTest {
  @Test
  @DisplayName("앞의 작업 하나를 끝내면 이어 읽기를 앞에, 발견한 작업을 맨 뒤에 둔다")
  void afterOneHead() {
    // given
    WorkQueue<String> queue = WorkQueue.of(List.of("a", "b", "c"));

    // when
    WorkQueue<String> next = queue.afterHead(1, List.of("a2"), List.of("d"));

    // then
    assertThat(next.items()).containsExactly("a2", "b", "c", "d");
  }

  @Test
  @DisplayName("앞의 배치(여러 개)를 끝내면 그 자리를 다음 작업으로 바꾼다")
  void afterBatch() {
    // given
    WorkQueue<String> queue = WorkQueue.of(List.of("a", "b", "c"));

    // when
    WorkQueue<String> next = queue.afterHead(2, List.of("x", "y"), List.of());

    // then
    assertThat(next.items()).containsExactly("x", "y", "c");
  }

  @Test
  @DisplayName("버리기는 지금 하는 첫 작업을 남긴다")
  void dropKeepsHead() {
    // given
    WorkQueue<String> queue = WorkQueue.of(List.of("x1", "a", "x2"));

    // when
    WorkQueue<String> kept = queue.withoutQueued(w -> w.startsWith("x"));

    // then
    assertThat(kept.items()).containsExactly("x1", "a");
  }
}
