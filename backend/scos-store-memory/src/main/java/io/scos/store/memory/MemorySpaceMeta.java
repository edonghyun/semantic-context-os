package io.scos.store.memory;

import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 메모리 참조 구현. 트랜잭션이 없으므로 getLocked는 get과 같다. */
public final class MemorySpaceMeta implements SpaceMeta {
  private final ConcurrentMap<SpaceId, ConcurrentMap<String, String>> values =
      new ConcurrentHashMap<>();

  @Override
  public Optional<String> get(SpaceId space, String key) {
    Map<String, String> keys = values.get(space);
    return keys == null ? Optional.empty() : Optional.ofNullable(keys.get(key));
  }

  @Override
  public void set(SpaceId space, String key, String value) {
    keysOf(space).put(key, value);
  }

  @Override
  public String putIfAbsent(SpaceId space, String key, String value) {
    String existing = keysOf(space).putIfAbsent(key, value);
    return existing == null ? value : existing;
  }

  private Map<String, String> keysOf(SpaceId space) {
    return values.computeIfAbsent(space, s -> new ConcurrentHashMap<>());
  }
}
