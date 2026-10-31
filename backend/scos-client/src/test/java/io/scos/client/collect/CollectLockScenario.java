package io.scos.client.collect;

import io.scos.support.ChildJvm;
import io.scos.support.Eventually;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CollectLockScenario implements AutoCloseable {
  private static final Duration WAIT = Duration.ofSeconds(10);
  private final Path file;
  private final Process holder;

  CollectLockScenario(Path root) throws Exception {
    this(root, holder -> {});
  }

  CollectLockScenario(Path root, Consumer<Process> beforeReady) throws Exception {
    file = root.resolve("collect.lock");
    Path ready = root.resolve("ready");
    holder =
        ChildJvm.start(
            LockHolderProbe.class, root.resolve("child.log"), file.toString(), ready.toString());
    try {
      beforeReady.accept(holder);
      awaitReady(ready);
    } catch (Exception | Error failure) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  static void awaitReady(Path ready) throws Exception {
    Eventually.await("given 준비 실패: 자식 JVM 잠금 보유", () -> Files.exists(ready), WAIT);
    if (!Files.readString(ready).equals("READY"))
      throw new IllegalStateException("given 준비 실패: READY 신호");
  }

  boolean tryAcquire() {
    Optional<CollectLock> acquired = CollectLock.tryAcquire(file);
    acquired.ifPresent(CollectLock::close);
    return acquired.isPresent();
  }

  void givenParentFailedToAcquire() {
    if (tryAcquire()) throw new IllegalStateException("given 준비 실패: 부모의 첫 획득은 실패해야 한다");
  }

  void releaseHolder() throws Exception {
    holder.getOutputStream().close();
    if (!holder.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS) || holder.exitValue() != 0)
      throw new IllegalStateException("given 준비 실패: 자식 JVM 잠금 해제");
  }

  @Override
  public void close() {
    // 준비 대기가 인터럽트됐어도 표시를 보존하면서 자식 회수를 마친다.
    if (holder.isAlive()) holder.destroyForcibly().onExit().join();
  }
}
