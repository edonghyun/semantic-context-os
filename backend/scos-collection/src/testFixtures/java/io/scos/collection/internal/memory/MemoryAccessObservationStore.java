package io.scos.collection.internal.memory;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.AccessMergePolicy;
import io.scos.collection.internal.AccessMergePolicy.Observations;
import io.scos.collection.internal.AccessObservationStore;
import io.scos.collection.internal.RunId;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemorySourceAccessDirectory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 관측 원장을 직접 소유하며 확정한 읽기 필드만 신원 저장소로 공개한다. */
public final class MemoryAccessObservationStore implements AccessObservationStore {
  private final MemorySourceAccessDirectory directory;
  private final MemoryRunStore.Records runStore;
  private final Map<RunId, Observations> runs = new HashMap<>();

  public MemoryAccessObservationStore(
      MemorySourceAccessDirectory directory, MemoryRunStore.Records runStore) {
    this.directory = directory;
    this.runStore = runStore;
  }

  @Override
  public void stage(RunId run, AclPage page) {
    directory.withLock(
        () -> {
          if (runStore.get(run).isEmpty()) throw new IllegalArgumentException("Run not found");
          runs.put(run, staged(run).observe(page));
        });
  }

  @Override
  public AccessMerge merge(
      RunId run,
      SourceInstanceId source,
      AccessMerge requested,
      IdentityCoverage identityCoverage,
      Instant at,
      Runnable guard) {
    return directory.withLock(
        () -> {
          if (!new AccessKeyInstances().admits(source))
            throw new IllegalArgumentException("Invalid access source instance");
          var owner =
              runStore.get(run).orElseThrow(() -> new IllegalArgumentException("Run not found"));
          if (!owner.source().equals(source))
            throw new IllegalArgumentException("Run belongs to another source");
          var observations = staged(run);
          var mode = observations.conflicted() ? AccessMerge.INCOMPLETE : requested;
          try {
            mode = apply(source, observations.snapshot(), mode, identityCoverage, at, guard);
          } catch (AccessMergePolicy.IncompleteSnapshotException incomplete) {
            mode = AccessMerge.INCOMPLETE;
            mode = apply(source, observations.snapshot(), mode, identityCoverage, at, guard);
          }
          return mode;
        });
  }

  private AccessMerge apply(
      SourceInstanceId source,
      Snapshot observed,
      AccessMerge mode,
      IdentityCoverage identityCoverage,
      Instant at,
      Runnable guard) {
    guard.run();
    if (mode == AccessMerge.INCOMPLETE && observed.equals(Snapshot.empty())) return mode;
    var merged = AccessMergePolicy.merge(mode, identityCoverage, snapshot(source), observed, at);
    var next = merged.snapshot();
    directory.save(source, next);
    return merged.mode();
  }

  @Override
  public void discard(RunId run) {
    directory.withLock(
        () -> {
          runs.remove(run);
        });
  }

  public String fingerprint(SourceInstanceId source, String group) {
    return directory.withLock(
        () -> {
          var value = directory.current(source).groups().get(group);
          return value == null ? null : value.fingerprint();
        });
  }

  public Snapshot snapshot(SourceInstanceId source) {
    return directory.withLock(() -> directory.current(source));
  }

  public Observations staged(RunId run) {
    return directory.withLock(() -> runs.getOrDefault(run, Observations.empty()));
  }
}
