package io.scos.spi.connector;

import java.util.Optional;

/** 어댑터 collect 입력. */
public record CollectionInput(
    SourceDescriptor source,
    Optional<Checkpoint> checkpoint,
    CollectionMode mode,
    CallExecutor calls,
    ContentCapture content,
    CancellationToken cancel,
    CollectionState state) {
  public CollectionInput(
      SourceDescriptor source,
      Optional<Checkpoint> checkpoint,
      CollectionMode mode,
      CallExecutor calls,
      ContentCapture content,
      CancellationToken cancel) {
    this(source, checkpoint, mode, calls, content, cancel, CollectionState.unavailable());
  }
}
