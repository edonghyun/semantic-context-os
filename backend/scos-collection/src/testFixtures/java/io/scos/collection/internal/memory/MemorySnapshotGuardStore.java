package io.scos.collection.internal.memory;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 메모리 참조 구현. */
public final class MemorySnapshotGuardStore implements SnapshotGuardStore {
  private final Map<SourceInstanceId, Guarded> guarded = new HashMap<>();
  private final Map<SourceInstanceId, Acceptance> acceptances = new HashMap<>();
  private final Map<SourceInstanceId, Map.Entry<RunId, Integer>> baselines = new HashMap<>();

  @Override
  public synchronized void recordGuarded(SourceInstanceId source, Guarded g) {
    guarded.put(source, g);
  }

  @Override
  public synchronized void clearGuarded(SourceInstanceId source) {
    guarded.remove(source);
  }

  @Override
  public synchronized Optional<Guarded> lastGuarded(SourceInstanceId source) {
    return Optional.ofNullable(guarded.get(source));
  }

  @Override
  public synchronized void acceptShrink(SourceInstanceId source, Instant at) {
    acceptances.put(
        source, new Acceptance(at, Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Override
  public synchronized Optional<Acceptance> lastAcceptance(SourceInstanceId source) {
    return Optional.ofNullable(acceptances.get(source));
  }

  @Override
  public synchronized boolean consumeAcceptance(SourceInstanceId source, RunId run, Instant at) {
    Acceptance current = acceptances.get(source);
    if (current == null || !current.pending()) return false;
    acceptances.put(
        source,
        new Acceptance(current.acceptedAt(), Optional.of(at), Optional.of(run), Optional.empty()));
    return true;
  }

  @Override
  public synchronized boolean expireAcceptance(
      SourceInstanceId source, Instant acceptedAt, Instant at) {
    Acceptance current = acceptances.get(source);
    if (current == null || !current.pending() || !current.acceptedAt().equals(acceptedAt))
      return false;
    acceptances.put(
        source,
        new Acceptance(current.acceptedAt(), Optional.empty(), Optional.empty(), Optional.of(at)));
    return true;
  }

  @Override
  public synchronized void recordBaseline(SourceInstanceId source, RunId run, int activeCount) {
    baselines.put(source, Map.entry(run, activeCount));
  }

  @Override
  public synchronized Optional<Integer> baseline(SourceInstanceId source, RunId run) {
    return Optional.ofNullable(baselines.get(source))
        .filter(b -> b.getKey().equals(run))
        .map(Map.Entry::getValue);
  }
}
