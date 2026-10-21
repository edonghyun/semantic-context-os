package io.scos.store.memory;

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

/** 메모리 참조 구현 — TS `VectorMatrix` 수식(순차 누적 dot/(normQ·normV), score>0, 동점 적재 순)을 그대로 둔다. */
public final class MemoryVectorIndex implements VectorIndex {
  private final Map<SpaceId, LinkedHashMap<String, IndexedUnit>> spaces = new LinkedHashMap<>();

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public synchronized List<List<ScoredRef>> search(
      SpaceId space, List<double[]> queries, int k, SearchFilter filter) {
    List<List<ScoredRef>> out = new ArrayList<>();
    var units = spaces.getOrDefault(space, new LinkedHashMap<>()).values();
    for (double[] q : queries) {
      double sumQ = 0;
      for (double x : q) sumQ += x * x;
      double normQ = Math.sqrt(sumQ);
      List<ScoredRef> hits = new ArrayList<>();
      for (IndexedUnit u : units) {
        if (!filter.accepts(u.partition(), u.archived())) continue;
        double[] v = u.embedding();
        if (v.length != q.length || normQ == 0) continue;
        double sumV = 0;
        for (double x : v) sumV += x * x;
        double normV = Math.sqrt(sumV);
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
    for (IndexedUnit u : units) map.put(u.unitId(), u); // 기존 키는 삽입 위치 유지
  }

  @Override
  public synchronized Map<String, double[]> vectorsOf(SpaceId space, List<String> unitIds) {
    var map = spaces.getOrDefault(space, new LinkedHashMap<>());
    Map<String, double[]> out = new LinkedHashMap<>();
    for (String id : unitIds) if (map.containsKey(id)) out.put(id, map.get(id).embedding().clone());
    return out;
  }

  @Override
  public synchronized void delete(SpaceId space, List<String> unitIds) {
    var map = spaces.get(space);
    if (map != null) unitIds.forEach(map::remove);
  }
}
