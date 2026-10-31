package io.scos.store.memory;

import io.scos.spi.access.DocumentAccess;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 메모리 참조 구현 — 수식: 순차 누적 dot/(normQ·normV), score>0, 동점 적재 순. normV는 적재할 때 한 번 센다. */
public final class MemoryVectorIndex implements VectorIndex {
  /** 적재한 유닛과 그 벡터의 노름(제곱합을 순서대로 누적한 뒤 sqrt — 질의 노름과 같은 계산). */
  private record Stored(IndexedUnit unit, double norm) {
    static Stored of(IndexedUnit unit) {
      return new Stored(unit, MemoryVectorIndex.norm(unit.embedding()));
    }
  }

  private final Map<SpaceId, LinkedHashMap<String, Stored>> spaces = new LinkedHashMap<>();
  private final MemoryAclStore acl;

  public MemoryVectorIndex() {
    this(new MemoryAclStore());
  }

  public MemoryVectorIndex(MemoryAclStore acl) {
    this.acl = acl;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public synchronized List<List<ScoredRef>> search(
      SpaceId space, List<double[]> queries, int k, SearchFilter filter) {
    List<List<ScoredRef>> out = new ArrayList<>();
    var units = spaces.getOrDefault(space, new LinkedHashMap<>()).values();
    DocumentAccess access = new DocumentAccess(filter.access());
    Map<String, DocumentAcl> acls =
        filter.access() instanceof AccessScope.Unrestricted
            ? Map.of()
            : acl.get(space, units.stream().map(stored -> stored.unit().documentId()).toList());
    for (double[] q : queries) {
      double normQ = norm(q);
      List<ScoredRef> hits = new ArrayList<>();
      for (Stored stored : units) {
        IndexedUnit u = stored.unit();
        if (!filter.accepts(u.partition(), u.archived())) continue;
        if (!access.admits(Optional.ofNullable(acls.get(u.documentId())))) continue;
        double[] v = u.embedding();
        if (v.length != q.length || normQ == 0) continue;
        double normV = stored.norm();
        if (normV == 0) continue;
        double dot = 0;
        for (int i = 0; i < q.length; i++) dot += q[i] * v[i];
        double score = dot / (normQ * normV);
        if (score > 0) hits.add(new ScoredRef(u.unitId(), u.documentId(), score));
      }
      hits.sort((a, b) -> Double.compare(b.score(), a.score()));
      out.add(hits.size() > k ? List.copyOf(hits.subList(0, k)) : List.copyOf(hits));
    }
    return out;
  }

  @Override
  public synchronized void upsert(SpaceId space, List<IndexedUnit> units) {
    var map = spaces.computeIfAbsent(space, s -> new LinkedHashMap<>());
    for (IndexedUnit u : units) map.put(u.unitId(), Stored.of(u)); // 기존 키는 삽입 위치 유지
  }

  @Override
  public synchronized Map<String, double[]> vectorsOf(
      SpaceId space, AccessScope access, List<String> unitIds) {
    if (access instanceof AccessScope.Unrestricted) return storedVectors(space, unitIds);
    var map = spaces.getOrDefault(space, new LinkedHashMap<>());
    DocumentAccess policy = new DocumentAccess(access);
    Map<String, DocumentAcl> acls =
        acl.get(
            space,
            unitIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(stored -> stored.unit().documentId())
                .toList());
    Map<String, double[]> out = new LinkedHashMap<>();
    for (String id : unitIds) {
      Stored stored = map.get(id);
      if (stored != null
          && policy.admits(Optional.ofNullable(acls.get(stored.unit().documentId()))))
        out.put(id, stored.unit().embedding().clone());
    }
    return out;
  }

  synchronized Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds) {
    var map = spaces.getOrDefault(space, new LinkedHashMap<>());
    Map<String, double[]> out = new LinkedHashMap<>();
    for (String id : unitIds)
      if (map.containsKey(id)) out.put(id, map.get(id).unit().embedding().clone());
    return out;
  }

  @Override
  public synchronized void delete(SpaceId space, List<String> unitIds) {
    var map = spaces.get(space);
    if (map != null) unitIds.forEach(map::remove);
  }

  private static double norm(double[] v) {
    double sum = 0;
    for (double x : v) sum += x * x;
    return Math.sqrt(sum);
  }
}
