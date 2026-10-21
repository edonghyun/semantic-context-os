package io.scos.collection.internal;

/** 유지보수 한 번(명령 `maintenance`·데몬 간격): blob GC(F9) + 변경 로그 보존(F10, 같은 유예). */
public final class Maintenance {
  public record Report(BlobGarbageCollector.Report blobs, int prunedChanges) {}

  private final BlobGarbageCollector blobs;
  private final SourceChangeLog changes;

  public Maintenance(BlobGarbageCollector blobs, SourceChangeLog changes) {
    this.blobs = blobs;
    this.changes = changes;
  }

  public Report run(GracePeriod grace, SweepMode mode) {
    BlobGarbageCollector.Report swept = blobs.sweep(grace, mode);
    int pruned =
        switch (mode) {
          case DRY_RUN -> 0;
          case DELETE -> changes.pruneBefore(blobs.cutoff(grace));
        };
    return new Report(swept, pruned);
  }
}
