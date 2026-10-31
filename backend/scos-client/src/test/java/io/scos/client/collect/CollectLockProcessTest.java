package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("process")
@DisplayName("프로세스 간 수집 잠금은 실패한 획득 뒤에도 유지된다(B10)")
class CollectLockProcessTest {
  @Test
  @DisplayName("중복 시도가 실패한 뒤에도 다른 프로세스는 잠금을 잡지 못한다")
  void duplicateAttemptKeepsOsLock(@TempDir Path dir) throws Exception {
    // given
    Path file = dir.resolve("collect.lock");
    CollectLock holder = CollectLock.tryAcquire(file).orElseThrow();
    CollectLock.tryAcquire(file);

    // when
    int whileHeld = probe(file);
    holder.close();
    int afterRelease = probe(file);

    // then
    assertThat(whileHeld).isEqualTo(3);
    assertThat(afterRelease).isZero();
  }

  @Test
  @DisplayName("다른 프로세스가 잠금을 쥐면 부모 JVM의 획득은 실패한다")
  void parentCannotAcquireHeldLock(@TempDir Path dir) throws Exception {
    // given
    try (var scenario = new CollectLockScenario(dir)) {
      // when
      boolean acquired = scenario.tryAcquire();
      // then
      assertThat(acquired).isFalse();
    }
  }

  @Test
  @DisplayName("부모 JVM의 획득 실패 뒤 보유자가 놓으면 같은 경로를 다시 잡는다")
  void parentCanRetryAfterFailure(@TempDir Path dir) throws Exception {
    // given
    try (var scenario = new CollectLockScenario(dir)) {
      scenario.givenParentFailedToAcquire();
      scenario.releaseHolder();
      // when
      boolean acquired = scenario.tryAcquire();
      // then
      assertThat(acquired).isTrue();
    }
  }

  private static int probe(Path file) throws Exception {
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LockProbe.class.getName(),
                file.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    return child.waitFor();
  }
}
