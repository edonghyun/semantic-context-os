package io.scos.collection.internal.memory;

import io.scos.collection.internal.MutationDecision;
import io.scos.collection.internal.RawAclStore;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
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
public class MemoryRawItemStore implements RawItemStore {
  private final Map<SourceInstanceId, TreeMap<String, RawRecord>> sources = new HashMap<>();
  private final Map<String, byte[]> blobs = new HashMap<>();

  private final RawAclStore acls;
  private final AclStore documents;
  private final MemoryDocumentOwnership ownership;
  private final Map<ExpiryKey, ExpiryClosure> expired = new HashMap<>();

  private record ExpiryKey(SourceInstanceId source, String id) {}

  private record ExpiryClosure(Instant observedAt, SpaceId space, Optional<String> document) {}

  public MemoryRawItemStore() {
    this(new MemoryRawAclStore());
  }

  public MemoryRawItemStore(RawAclStore acls) {
    this(acls, new MemoryAclStore());
  }

  public MemoryRawItemStore(RawAclStore acls, AclStore documents) {
    this(acls, documents, new MemoryDocumentOwnership());
  }

  public MemoryRawItemStore(
      RawAclStore acls, AclStore documents, MemoryDocumentOwnership ownership) {
    this.acls = acls;
    this.documents = documents;
    this.ownership = ownership;
  }

  @Override
  public synchronized void completeAccessExpiry(
      SourceInstanceId source,
      String externalId,
      Instant observedAt,
      SpaceId space,
      Optional<String> document) {
    if (!records(source).containsKey(externalId)) return;
    var acl = acls.get(source, List.of(externalId)).get(externalId);
    if (acl != null && isExpired(acl) && acl.observedAt().equals(observedAt))
      expired.put(
          new ExpiryKey(source, externalId), new ExpiryClosure(observedAt, space, document));
  }

  private boolean pendingExpiry(SourceInstanceId source, RawItem item) {
    var closure = expired.get(new ExpiryKey(source, item.externalId()));
    if (!(item.acl() instanceof RawAcl.Observed acl)
        || !isExpired(acl)
        || closure == null
        || !closure.observedAt().equals(acl.observedAt())) return true;
    return closure
        .document()
        .filter(id -> ownership.canClaim(closure.space(), source.value(), id))
        .map(id -> documents.get(closure.space(), List.of(id)).get(id))
        .filter(document -> document.status() != Status.FAILED)
        .isPresent();
  }

  private boolean isExpired(RawAcl.Observed acl) {
    return acl.status() == Status.FAILED && acl.reason() == AccessFailureReason.EXPIRED;
  }

  public RawAclStore acls() {
    return acls;
  }

  private RawItem withAcl(SourceInstanceId source, RawItem item) {
    RawAcl acl =
        Optional.<RawAcl>ofNullable(
                acls.get(source, List.of(item.externalId())).get(item.externalId()))
            .orElseGet(RawAcl::notObserved);
    return new RawItem(
        item.externalId(),
        item.resourceType(),
        item.sourceVersion(),
        item.sourceOrder(),
        item.sourceCreatedAt(),
        item.sourceUpdatedAt(),
        item.observedAt(),
        item.metadataJson(),
        item.content(),
        acl);
  }

  private NavigableMap<String, RawRecord> records(SourceInstanceId source) {
    return sources.computeIfAbsent(source, s -> new TreeMap<>());
  }

  @Override
  public synchronized Outcome apply(SourceInstanceId source, RawMutation mutation) {
    NavigableMap<String, RawRecord> map = records(source);
    Optional<RawRecord> existing = Optional.ofNullable(map.get(mutation.externalId()));
    MutationDecision decision = MutationDecision.of(existing, mutation);
    switch (decision) {
      case MutationDecision.Unchanged unchanged -> {}
      case MutationDecision.WriteActive write -> {
        RawItem item = withStoredBlob(write.item());
        map.put(item.externalId(), new RawRecord.Active(item));
      }
      case MutationDecision.WriteTombstone write -> {
        map.put(write.tombstone().externalId(), write.tombstone());
        acls.delete(source, List.of(write.tombstone().externalId()));
      }
    }
    return decision.outcome();
  }

  /** blob 바이트는 따로 두고 항목에는 저장된 참조만 남긴다. */
  private RawItem withStoredBlob(RawItem item) {
    if (!(item.content().orElse(null) instanceof RawContent.Blob b) || b.data().isEmpty())
      return item;
    blobs.putIfAbsent(b.sha256(), b.data().get());
    return item.withContent(b.stored());
  }

  @Override
  public synchronized Optional<RawRecord> get(SourceInstanceId source, String externalId) {
    return Optional.ofNullable(records(source).get(externalId))
        .map(
            record ->
                switch (record) {
                  case RawRecord.Active active ->
                      new RawRecord.Active(withAcl(source, active.item()));
                  case RawRecord.Deleted deleted -> deleted;
                });
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
      items.add(withAcl(source, a.item()));
    }
    return new Page(items, Optional.ofNullable(next));
  }

  @Override
  public synchronized Page accessCandidates(
      SourceInstanceId source,
      Set<String> kinds,
      Optional<Instant> cutoff,
      int limit,
      Optional<String> cursor) {
    if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
    List<RawItem> rows =
        records(source).values().stream()
            .filter(RawRecord.Active.class::isInstance)
            .map(RawRecord.Active.class::cast)
            .map(RawRecord.Active::item)
            .filter(item -> kinds.contains(item.resourceType()))
            .filter(
                item -> cursor.isEmpty() || item.externalId().compareTo(cursor.orElseThrow()) > 0)
            .map(item -> accessItem(withAcl(source, item)))
            .filter(
                item ->
                    cutoff.isEmpty()
                        || (item.acl() instanceof RawAcl.Observed acl
                            && !acl.observedAt().isAfter(cutoff.orElseThrow())
                            && pendingExpiry(source, item)))
            .limit((long) limit + 1)
            .toList();
    if (rows.size() <= limit) return new Page(rows, Optional.empty());
    return new Page(rows.subList(0, limit), Optional.of(rows.get(limit - 1).externalId()));
  }

  private RawItem accessItem(RawItem item) {
    return new RawItem(
        item.externalId(),
        item.resourceType(),
        item.sourceVersion(),
        item.sourceOrder(),
        item.sourceCreatedAt(),
        item.sourceUpdatedAt(),
        item.observedAt(),
        item.metadataJson(),
        Optional.empty(),
        item.acl());
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
    acls.delete(source, n);
    return n;
  }

  @Override
  public synchronized List<String> tombstoneSelected(
      SourceInstanceId source, Set<String> ids, Instant at) {
    List<String> removed = new ArrayList<>();
    for (String id : ids) {
      if (!(records(source).get(id) instanceof RawRecord.Active active)) continue;
      records(source)
          .put(
              id,
              new RawRecord.Deleted(
                  id,
                  Optional.of(active.item().resourceType()),
                  Optional.of(active.item().sourceVersion()),
                  Optional.empty(),
                  at,
                  at));
      removed.add(id);
    }
    acls.delete(source, removed);
    return List.copyOf(removed);
  }

  @Override
  public synchronized Optional<byte[]> blob(String sha256) {
    return Optional.ofNullable(blobs.get(sha256)).map(byte[]::clone);
  }
}
