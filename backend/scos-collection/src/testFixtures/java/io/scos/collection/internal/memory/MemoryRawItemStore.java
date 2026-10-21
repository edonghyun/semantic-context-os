package io.scos.collection.internal.memory;

import io.scos.collection.internal.MutationRules;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** 메모리 참조 구현(F4). 소스별 TreeMap(externalId 코드 유닛 순). */
public final class MemoryRawItemStore implements RawItemStore {
  private final Map<SourceInstanceId, TreeMap<String, RawRecord>> sources = new HashMap<>();
  private final Map<String, byte[]> blobs = new HashMap<>();

  private NavigableMap<String, RawRecord> records(SourceInstanceId source) {
    return sources.computeIfAbsent(source, s -> new TreeMap<>());
  }

  @Override
  public synchronized Outcome apply(SourceInstanceId source, RawMutation mutation) {
    NavigableMap<String, RawRecord> map = records(source);
    Optional<RawRecord> existing = Optional.ofNullable(map.get(mutation.externalId()));
    return switch (mutation) {
      case RawMutation.Upsert u -> {
        if (MutationRules.isStale(existing, u.item().sourceOrder())) yield Outcome.UNCHANGED;
        if (existing.orElse(null) instanceof RawRecord.Active a
            && a.item().sourceVersion().equals(u.item().sourceVersion())) yield Outcome.UNCHANGED;
        RawItem item = u.item();
        if (item.content().orElse(null) instanceof RawContent.Blob b && b.data().isPresent()) {
          blobs.putIfAbsent(b.sha256(), b.data().get());
          item = item.withContent(b.stored());
        }
        map.put(item.externalId(), new RawRecord.Active(item));
        yield Outcome.UPSERTED;
      }
      case RawMutation.Delete d -> {
        if (MutationRules.isStale(existing, d.sourceOrder())) yield Outcome.UNCHANGED;
        map.put(d.externalId(), MutationRules.tombstone(existing, d));
        yield Outcome.DELETED;
      }
    };
  }

  @Override
  public synchronized Optional<RawRecord> get(SourceInstanceId source, String externalId) {
    return Optional.ofNullable(records(source).get(externalId));
  }

  @Override
  public synchronized Page listActive(SourceInstanceId source, int limit, Optional<String> cursor) {
    if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
    List<RawItem> items = new ArrayList<>();
    var tail = cursor.map(c -> records(source).tailMap(c, false)).orElse(records(source));
    String next = null;
    for (RawRecord r : tail.values()) {
      if (!(r instanceof RawRecord.Active a)) continue;
      if (items.size() == limit) {
        next = items.get(items.size() - 1).externalId();
        break;
      }
      items.add(a.item());
    }
    return new Page(items, Optional.ofNullable(next));
  }

  @Override
  public synchronized int count(SourceInstanceId source) {
    return (int)
        records(source).values().stream().filter(r -> r instanceof RawRecord.Active).count();
  }

  @Override
  public synchronized int tombstoneCount(SourceInstanceId source) {
    return (int)
        records(source).values().stream().filter(r -> r instanceof RawRecord.Deleted).count();
  }

  @Override
  public synchronized int countUnobserved(SourceInstanceId source, Set<String> observed) {
    return (int)
        records(source).entrySet().stream()
            .filter(e -> e.getValue() instanceof RawRecord.Active && !observed.contains(e.getKey()))
            .count();
  }

  @Override
  public synchronized List<String> tombstoneUnobserved(
      SourceInstanceId source, Set<String> observed, Instant at) {
    List<String> n = new ArrayList<>();
    for (var e : records(source).entrySet()) {
      if (!(e.getValue() instanceof RawRecord.Active a) || observed.contains(e.getKey())) continue;
      e.setValue(
          new RawRecord.Deleted(
              e.getKey(),
              Optional.of(a.item().resourceType()),
              Optional.of(a.item().sourceVersion()),
              Optional.empty(),
              at,
              at));
      n.add(e.getKey());
    }
    return n;
  }

  @Override
  public synchronized Optional<byte[]> blob(String sha256) {
    return Optional.ofNullable(blobs.get(sha256)).map(byte[]::clone);
  }
}
