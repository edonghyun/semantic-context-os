package io.scos.collection.internal.memory;

import io.scos.collection.internal.CheckpointConflictException;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class MemoryCheckpointPort implements CheckpointPort {
  private final Map<SourceInstanceId, StoredCheckpoint> stored = new HashMap<>();

  @Override
  public synchronized StoredCheckpoint read(SourceInstanceId source) {
    return stored.getOrDefault(source, StoredCheckpoint.none());
  }

  @Override
  public synchronized long advance(
      SourceInstanceId source, long expectedRevision, Checkpoint next, Instant now) {
    StoredCheckpoint current = read(source);
    if (current.revision() != expectedRevision)
      throw new CheckpointConflictException(expectedRevision, current.revision());
    long revision = current.revision() + 1;
    stored.put(source, new StoredCheckpoint(revision, Optional.of(next)));
    return revision;
  }
}
