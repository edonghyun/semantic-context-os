package io.scos.store.memory;

import io.scos.spi.access.DocumentAccess;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 메모리 참조 구현 — LinkedHashMap 삽입 순서 = 적재 순서(seq). */
public final class MemoryUnitStore implements UnitStore {
  private final Map<SpaceId, LinkedHashMap<String, ContextUnit>> spaces = new LinkedHashMap<>();
  private final Map<String, SpaceId> owners = new LinkedHashMap<>();

  private final MemoryAclStore acl;

  public MemoryUnitStore() {
    this(new MemoryAclStore());
  }

  public MemoryUnitStore(MemoryAclStore acl) {
    this.acl = acl;
  }

  private Map<String, ContextUnit> space(SpaceId s) {
    return spaces.getOrDefault(s, new LinkedHashMap<>());
  }

  synchronized long countCompacted() {
    return spaces.values().stream()
        .flatMap(units -> units.values().stream())
        .filter(unit -> unit.tier() == Tier.COMPACTED)
        .count();
  }

  synchronized void upsert(SpaceId space, List<ContextUnit> units) {
    for (ContextUnit u : units) {
      SpaceId owner = owners.get(u.documentId());
      if (owner != null && !owner.equals(space))
        throw new SpaceOwnershipException(u.documentId(), owner, space);
    }
    for (ContextUnit u : units) owners.putIfAbsent(u.documentId(), space);
    var map = spaces.computeIfAbsent(space, s -> new LinkedHashMap<>());
    for (ContextUnit u : units) map.put(u.id(), u);
  }

  @Override
  public List<ContextUnit> getMany(SpaceId space, AccessScope access, List<String> ids) {
    return previousUnits(space, ids).stream()
        .filter(unit -> visible(space, access, unit.documentId()))
        .toList();
  }

  private boolean visible(SpaceId space, AccessScope access, String documentId) {
    if (access instanceof AccessScope.Unrestricted) return true;
    return new DocumentAccess(access)
        .admits(Optional.ofNullable(acl.get(space, List.of(documentId)).get(documentId)));
  }

  synchronized List<ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    var map = space(space);
    List<ContextUnit> out = new ArrayList<>();
    for (String id : ids) if (map.containsKey(id)) out.add(map.get(id));
    return out;
  }

  @Override
  public synchronized List<ContextUnit> listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range) {
    if (!visible(space, access, documentId)) return List.of();
    List<ContextUnit> all =
        space(space).values().stream()
            .filter(u -> u.documentId().equals(documentId))
            .filter(u -> access instanceof AccessScope.Unrestricted || u.tier() != Tier.COMPACTED)
            .toList();
    if (range.offset() >= all.size()) return List.of();
    return List.copyOf(
        all.subList(range.offset(), Math.min(all.size(), range.offset() + range.limit())));
  }

  @Override
  public synchronized List<LinkedUnit> linkedUnits(
      SpaceId space, AccessScope access, List<String> unitIds) {
    var map = space(space);
    Set<String> targets = new HashSet<>();
    for (String id : unitIds) if (map.containsKey(id)) targets.addAll(map.get(id).links());
    Set<String> exclude = new HashSet<>(unitIds);
    List<LinkedUnit> out = new ArrayList<>();
    for (ContextUnit u : map.values())
      if (targets.contains(u.documentId())
          && !exclude.contains(u.id())
          && visible(space, access, u.documentId()))
        out.add(new LinkedUnit(u.id(), u.documentId()));
    return out;
  }

  @Override
  public synchronized List<DocumentInfo> getDocuments(
      SpaceId space, AccessScope access, List<String> documentIds) {
    List<DocumentInfo> out = new ArrayList<>();
    for (String doc : documentIds) {
      if (!visible(space, access, doc)) continue;
      List<ContextUnit> units =
          space(space).values().stream().filter(u -> u.documentId().equals(doc)).toList();
      if (units.isEmpty()) continue;
      ContextUnit first = units.get(0);
      Optional<String> latest =
          units.stream()
              .map(u -> u.provenance().capturedAt())
              .filter(s -> !s.isEmpty())
              .max(String::compareTo);
      out.add(
          new DocumentInfo(
              doc,
              first.title(),
              first.provenance().uri(),
              first.provenance().sourceId(),
              units.size(),
              latest));
    }
    return out;
  }

  synchronized Optional<String> latestCapturedAt(SpaceId space) {
    return space(space).values().stream()
        .map(u -> u.provenance().capturedAt())
        .filter(s -> !s.isEmpty())
        .max(String::compareTo);
  }

  synchronized List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    Set<String> keep = new HashSet<>(keepIds);
    List<String> removed = new ArrayList<>();
    var it = space(space).entrySet().iterator();
    while (it.hasNext()) {
      var e = it.next();
      ContextUnit u = e.getValue();
      if (u.documentId().equals(documentId)
          && u.provenance().sourceId().equals(sourceId)
          && !keep.contains(u.id())) {
        removed.add(u.id());
        it.remove();
      }
    }
    return removed;
  }

  synchronized List<String> listDocumentIds(SpaceId space, String sourceId) {
    Set<String> out = new LinkedHashSet<>();
    for (ContextUnit u : space(space).values())
      if (u.provenance().sourceId().equals(sourceId)) out.add(u.documentId());
    return new ArrayList<>(out);
  }

  synchronized List<ContextUnit> listAll(SpaceId space) {
    return List.copyOf(space(space).values());
  }
}
