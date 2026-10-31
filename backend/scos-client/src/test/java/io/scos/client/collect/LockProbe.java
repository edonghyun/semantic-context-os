package io.scos.client.collect;

import java.nio.file.Path;

/** CollectLockTest가 자식 JVM으로 띄우는 탐침: 잠금을 잡으면 종료 코드 0, 못 잡으면 3. */
public final class LockProbe {
  private LockProbe() {}

  public static void main(String[] args) {
    var lock = CollectLock.tryAcquire(Path.of(args[0]));
    lock.ifPresent(CollectLock::close);
    System.exit(lock.isPresent() ? 0 : 3);
  }
}
