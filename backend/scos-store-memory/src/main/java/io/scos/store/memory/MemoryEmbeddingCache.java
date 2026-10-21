package io.scos.store.memory;

import io.scos.spi.store.EmbeddingCache;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 메모리 참조 구현(계약 {@code EmbeddingCacheContract}). 벡터를 double 그대로 둔다 — PostgreSQL의 float4 저장 정밀도는 모사하지
 * 않으므로, 저장 뒤 값을 비교하는 계약은 허용 오차를 둔다(CONTRACTS F-7).
 */
public final class MemoryEmbeddingCache implements EmbeddingCache {
  private final Map<String, Map<String, double[]>> byGeneration = new HashMap<>();

  @Override
  public synchronized Map<String, double[]> find(String generation, Collection<String> inputKeys) {
    Map<String, double[]> stored = byGeneration.getOrDefault(generation, Map.of());
    Map<String, double[]> out = new LinkedHashMap<>();
    for (String key : inputKeys) if (stored.containsKey(key)) out.put(key, stored.get(key).clone());
    return out;
  }

  @Override
  public synchronized int putIfAbsent(String generation, Map<String, double[]> entries) {
    Map<String, double[]> stored = byGeneration.computeIfAbsent(generation, g -> new HashMap<>());
    int inserted = 0;
    for (Map.Entry<String, double[]> e : entries.entrySet())
      if (stored.putIfAbsent(e.getKey(), e.getValue().clone()) == null) inserted++;
    return inserted;
  }

  @Override
  public synchronized int size() {
    return byGeneration.values().stream().mapToInt(Map::size).sum();
  }

  @Override
  public synchronized int clear() {
    int n = size();
    byGeneration.clear();
    return n;
  }
}
