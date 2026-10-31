package io.scos.client.collect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** stdin EOF까지 OS 잠금을 쥐고 READY 줄로 부모에 소유권을 알린다. */
public final class LockHolderProbe {
  private LockHolderProbe() {}

  public static void main(String[] args) throws Exception {
    try (var held = CollectLock.tryAcquire(Path.of(args[0])).orElseThrow()) {
      Path ready = Path.of(args[1]);
      Path pending = ready.resolveSibling("ready.pending");
      Files.writeString(pending, "READY");
      Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
      while (System.in.read() != -1) {
        // 부모가 stdin을 닫을 때까지 잠금을 유지한다.
      }
    }
  }
}
