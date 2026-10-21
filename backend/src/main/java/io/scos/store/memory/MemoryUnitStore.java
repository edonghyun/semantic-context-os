package io.scos.store.memory;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import io.scos.spi.store.UnitStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 메모리 참조 구현 — LinkedHashMap 삽입 순서 = 적재 순서(seq). */
public final class MemoryUnitStore implements UnitStore {
  private final Map<SpaceId, LinkedHashMap<String, ContextUnit>> spaces = new LinkedHashMap<>();
  private final Map<String, SpaceId> owners = new LinkedHashMap<>();

  private Map<String, ContextUnit> space(SpaceId s) {
    return spaces.getOrDefault(s, new LinkedHashMap<>());
  }

  @Override
  public synchronized void upsert(SpaceId space, List<ContextUnit> units) {
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
  public synchronized List<ContextUnit> getMany(SpaceId space, List<String> ids) {
    var map = space(space);
    List<ContextUnit> out = new ArrayList<>();
    for (String id : ids) if (map.containsKey(id)) out.add(map.get(id));
    return out;
  }

  @Override
  public synchronized List<ContextUnit> listByDocument(
      SpaceId space, String documentId, Range range) {
    List<ContextUnit> all =
        space(space).values().stream().filter(u -> u.documentId().equals(documentId)).toList();
    if (range.offset() >= all.size()) return List.of();
    return List.copyOf(
        all.subList(range.offset(), Math.min(all.size(), range.offset() + range.limit())));
  }

  @Override
  public synchronized List<LinkedUnit> linkedUnits(SpaceId space, List<String> unitIds) {
    var map = space(space);
    Set<String> targets = new HashSet<>();
    for (String id : unitIds) if (map.containsKey(id)) targets.addAll(map.get(id).links());
    Set<String> exclude = new HashSet<>(unitIds);
    List<LinkedUnit> out = new ArrayList<>();
    for (ContextUnit u : map.values())
      if (targets.contains(u.documentId()) && !exclude.contains(u.id()))
        out.add(new LinkedUnit(u.id(), u.documentId()));
    return out;
  }

  @Override
  public synchronized List<DocumentInfo> getDocuments(SpaceId space, List<String> documentIds) {
    List<DocumentInfo> out = new ArrayList<>();
    for (String doc : documentIds) {
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

  @Override
  public synchronized Optional<String> latestCapturedAt(SpaceId space) {
    return space(space).values().stream()
        .map(u -> u.provenance().capturedAt())
        .filter(s -> !s.isEmpty())
        .max(String::compareTo);
  }

  @Override
  public synchronized List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    Set<String> keep = new HashSet<>(keepIds);
    List<String> removed = new ArrayList<>();
    var it = spaces.computeIfAbsent(space, k -> new LinkedHashMap<>()).entrySet().iterator();
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

  @Override
  public synchronized List<String> listDocumentIds(SpaceId space, String sourceId) {
    List<String> out = new ArrayList<>();
    for (ContextUnit u : space(space).values())
      if (u.provenance().sourceId().equals(sourceId) && !out.contains(u.documentId()))
        out.add(u.documentId());
    return out;
  }

  @Override
  public synchronized List<ContextUnit> listAll(SpaceId space) {
    return List.copyOf(space(space).values());
  }
}
