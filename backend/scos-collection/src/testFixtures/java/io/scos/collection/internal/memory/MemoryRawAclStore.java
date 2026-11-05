package io.scos.collection.internal.memory;

import io.scos.collection.internal.RawAclDecision;
import io.scos.collection.internal.RawAclStore;
import io.scos.spi.access.AclStore;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 원본 ACL 참조 구현. 소스별 저장과 결정을 같은 잠금 안에서 적용한다. */
public final class MemoryRawAclStore implements RawAclStore {
  private final Map<SourceInstanceId, Map<String, RawAcl.Observed>> sources = new HashMap<>();

  private final UnitMaintenance units;
  private final AclStore documents;

  public MemoryRawAclStore() {
    this(new MemoryAclStore());
  }

  private MemoryRawAclStore(MemoryAclStore documents) {
    this(
        new MemoryUnitMaintenance(new MemoryUnitStore(documents), new MemoryVectorIndex()),
        documents);
  }

  public MemoryRawAclStore(UnitMaintenance units, AclStore documents) {
    this.units = units;
    this.documents = documents;
  }

  @Override
  public int documentsMissingAcl(SpaceId space, SourceInstanceId source) {
    var ids = units.listDocumentIds(space, source.value());
    return ids.size() - documents.get(space, ids).size();
  }

  @Override
  public synchronized boolean observe(
      SourceInstanceId source, String externalId, RawAcl acl, Instant fallbackAt) {
    Map<String, RawAcl.Observed> rows = sources.computeIfAbsent(source, ignored -> new HashMap<>());
    var previous = rows.get(externalId);
    RawAclDecision decision =
        RawAclDecision.of(source, Optional.ofNullable(previous), acl, fallbackAt);
    rows.put(externalId, decision.next());
    return decision.semanticChange()
        || (previous != null && previous.reason() != decision.next().reason());
  }

  @Override
  public synchronized Map<String, RawAcl.Observed> get(
      SourceInstanceId source, Collection<String> externalIds) {
    Map<String, RawAcl.Observed> rows = sources.getOrDefault(source, Map.of());
    Map<String, RawAcl.Observed> selected = new HashMap<>();
    for (String id : externalIds) {
      RawAcl.Observed acl = rows.get(id);
      if (acl != null) selected.put(id, acl);
    }
    return Map.copyOf(selected);
  }

  @Override
  public synchronized void delete(SourceInstanceId source, Collection<String> externalIds) {
    Map<String, RawAcl.Observed> rows = sources.get(source);
    if (rows != null) externalIds.forEach(rows::remove);
  }
}
