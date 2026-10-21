package io.scos.collection.internal.memory;

import io.scos.collection.internal.ChangeCursor;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 메모리 참조 구현(F10). */
public final class MemorySourceChangeLog implements SourceChangeLog {
  private final List<Change> changes = new ArrayList<>();
  private long head;
  private long floor;

  @Override
  public synchronized void appendAll(List<Entry> entries) {
    for (Entry e : entries)
      changes.add(
          new Change(
              ++head,
              e.source(),
              e.externalId(),
              e.kind(),
              e.sourceVersion(),
              e.metadataJson(),
              e.occurredAt()));
  }

  @Override
  public synchronized Page read(Optional<SourceInstanceId> source, long after, int limit) {
    ChangeCursor.validate(after, limit, floor, head);
    List<Change> page =
        changes.stream()
            .filter(c -> c.seq() > after)
            .filter(c -> source.map(s -> s.equals(c.source())).orElse(true))
            .limit(limit)
            .toList();
    return new Page(page, page.isEmpty() ? head : page.get(page.size() - 1).seq(), head);
  }

  @Override
  public synchronized long head() {
    return head;
  }

  @Override
  public synchronized int pruneBefore(Instant cutoff) {
    long through =
        changes.stream()
            .filter(c -> c.occurredAt().isBefore(cutoff))
            .mapToLong(Change::seq)
            .max()
            .orElse(0);
    if (through == 0) return 0;
    int before = changes.size();
    changes.removeIf(c -> c.seq() <= through);
    floor = Math.max(floor, through);
    return before - changes.size();
  }

  /** 테스트 관찰용. */
  public synchronized List<Change> all() {
    return List.copyOf(changes);
  }
}
