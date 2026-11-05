package io.scos.collection.internal.memory;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.AccessMergePolicy;
import io.scos.collection.internal.AccessMergePolicy.Observations;
import io.scos.collection.internal.AccessObservationStore;
import io.scos.collection.internal.RunId;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.connector.AclPage;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 관측 원장을 직접 소유하며 확정한 읽기 필드만 신원 저장소로 공개한다. */
public final class MemoryAccessObservationStore implements AccessObservationStore {
  private final SourceAccessDirectory directory;
  private final MemoryRunStore.Records runStore;
  private final Map<RunId, Observations> runs = new HashMap<>();

  public MemoryAccessObservationStore(
      SourceAccessDirectory directory, MemoryRunStore.Records runStore) {
    this.directory = directory;
    this.runStore = runStore;
  }

  @Override
  public synchronized void stage(RunId run, AclPage page) {
    if (runStore.get(run).isEmpty()) throw new IllegalArgumentException("Run not found");
    runs.put(run, staged(run).observe(page));
  }

  @Override
  public synchronized AccessMerge merge(
      RunId run, SourceInstanceId source, AccessMerge requested, Instant at, Runnable guard) {
    if (!new AccessKeyInstances().admits(source))
      throw new IllegalArgumentException("Invalid access source instance");
    var owner = runStore.get(run).orElseThrow(() -> new IllegalArgumentException("Run not found"));
    if (!owner.source().equals(source))
      throw new IllegalArgumentException("Run belongs to another source");
    var observations = staged(run);
    var mode = observations.conflicted() ? AccessMerge.INCOMPLETE : requested;
    try {
      mode = apply(source, observations.snapshot(), mode, at, guard);
    } catch (AccessMergePolicy.IncompleteSnapshotException incomplete) {
      mode = AccessMerge.INCOMPLETE;
      mode = apply(source, observations.snapshot(), mode, at, guard);
    }
    return mode;
  }

  private AccessMerge apply(
      SourceInstanceId source, Snapshot observed, AccessMerge mode, Instant at, Runnable guard) {
    guard.run();
    if (mode == AccessMerge.INCOMPLETE && observed.equals(Snapshot.empty())) return mode;
    var merged = AccessMergePolicy.merge(mode, snapshot(source), observed, at);
    var next = merged.snapshot();
    directory.save(source, next);
    return merged.mode();
  }

  @Override
  public synchronized void discard(RunId run) {
    runs.remove(run);
  }

  public synchronized String fingerprint(SourceInstanceId source, String group) {
    var value = directory.current(source).groups().get(group);
    return value == null ? null : value.fingerprint();
  }

  public synchronized Snapshot snapshot(SourceInstanceId source) {
    return directory.current(source);
  }

  public synchronized Observations staged(RunId run) {
    return runs.getOrDefault(run, Observations.empty());
  }
}
