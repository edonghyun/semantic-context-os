package io.scos.spi.connector;

import java.util.Optional;

/** 어댑터 collect 입력(TS SourceAdapter.collect input). */
public record CollectionInput(
    SourceDescriptor source,
    Optional<Checkpoint> checkpoint,
    CollectionMode mode,
    CallExecutor calls,
    ContentCapture content,
    CancellationToken cancel) {}
