package io.scos.collection.internal;

/** 기대한 revision과 저장된 revision이 다르다 — 다른 run이 먼저 전진했다. 재시도 가능 분류(checkpoint_conflict). */
public class CheckpointConflictException extends RuntimeException {
  public CheckpointConflictException(long expected, long actual) {
    super("checkpoint revision conflict: expected " + expected + ", actual " + actual);
  }
}
