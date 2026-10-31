package io.scos.client.collect;

import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 준비 장애 시점에 실제 잠금을 쥔 자식을 관찰하고, 실패한 검증에서도 회수한다. */
final class CollectLockPreparationScenario implements AutoCloseable {
  private final Path root;
  private Process holder;

  CollectLockPreparationScenario(Path root) {
    this.root = root;
  }

  Throwable failPreparation(Throwable failure) {
    return catchThrowable(
        () -> new CollectLockScenario(root, process -> failAfterReady(process, failure)));
  }

  Throwable rejectInvalidReady() {
    return catchThrowable(() -> new CollectLockScenario(root, this::invalidateReady));
  }

  private void invalidateReady(Process process) {
    awaitHolder(process);
    try {
      Files.writeString(root.resolve("ready"), "INVALID");
    } catch (IOException e) {
      throw new UncheckedIOException("given 준비 실패: 잘못된 READY 신호", e);
    }
  }

  private void awaitHolder(Process process) {
    holder = process;
    try {
      CollectLockScenario.awaitReady(root.resolve("ready"));
    } catch (Exception e) {
      throw new IllegalStateException("given 준비 실패: 잠금 보유 신호", e);
    }
    if (!holder.isAlive()) throw new IllegalStateException("given 준비 실패: 자식 JVM 생존");
  }

  private void failAfterReady(Process process, Throwable failure) {
    awaitHolder(process);
    if (failure instanceof RuntimeException exception) throw exception;
    throw (Error) failure;
  }

  boolean holderAlive() {
    return holder.isAlive();
  }

  @Override
  public void close() {
    if (holder != null && holder.isAlive()) holder.destroyForcibly().onExit().join();
  }
}
