package io.scos.collection.internal;

import java.time.Clock;
import java.time.Instant;

/**
 * F9(TS BlobGarbageCollector.sweep): 참조 밖이고 유예보다 오래된 blob만 지운다. DRY_RUN은 지우지 않고 지울 수를 보고한다(TS와 같이
 * deleted·bytesFreed는 "지울/지운" 수).
 */
public final class BlobGarbageCollector {
  public record Report(
      long scanned,
      long deleted,
      long retainedByReference,
      long retainedByGrace,
      long bytesFreed,
      SweepMode mode) {}

  private final BlobMaintenancePort blobs;
  private final Clock clock;

  public BlobGarbageCollector(BlobMaintenancePort blobs, Clock clock) {
    this.blobs = blobs;
    this.clock = clock;
  }

  public Report sweep(GracePeriod grace, SweepMode mode) {
    Instant cutoff = cutoff(grace);
    BlobMaintenancePort.BlobStats stats = blobs.stats(cutoff);
    long deleted =
        switch (mode) {
          case DRY_RUN -> stats.deletable();
          case DELETE -> blobs.deleteUnreferencedBefore(cutoff);
        };
    return new Report(
        stats.scanned(),
        deleted,
        stats.retainedByReference(),
        stats.retainedByGrace(),
        stats.bytes(),
        mode);
  }

  /** 이 시각보다 오래된 것이 유예 밖이다(blob GC·변경 로그 보존이 같은 기준을 쓴다). */
  public Instant cutoff(GracePeriod grace) {
    return grace.cutoff(clock.instant());
  }
}
