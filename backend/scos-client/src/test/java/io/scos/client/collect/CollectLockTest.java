package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("기기당 수집 잠금")
class CollectLockTest {
  @Test
  @DisplayName("이전 손잡이를 두 번 닫아도 새 보유자의 잠금은 유지된다")
  void closingTwiceKeepsNextHolder(@TempDir Path dir) {
    // given
    Path file = dir.resolve("collect.lock");
    CollectLock previous = CollectLock.tryAcquire(file).orElseThrow();
    previous.close();
    try (CollectLock current = CollectLock.tryAcquire(file).orElseThrow()) {
      // when
      previous.close();

      // then
      Optional<CollectLock> contender = CollectLock.tryAcquire(file);
      contender.ifPresent(CollectLock::close);
      assertThat(contender).isEmpty();
    }
  }

  @Test
  @DisplayName("잠금은 기기에서 하나만 잡히고, 놓으면 다시 잡힌다")
  void singleLockHolder(@TempDir Path dir) {
    // given
    Path file = dir.resolve("run/collect.lock");

    // when
    Optional<CollectLock> first = CollectLock.tryAcquire(file);
    Optional<CollectLock> second = CollectLock.tryAcquire(file);
    first.orElseThrow().close();
    Optional<CollectLock> third = CollectLock.tryAcquire(file);

    // then
    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    assertThat(third).isPresent();
    third.orElseThrow().close();
  }
}
