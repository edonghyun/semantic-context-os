package io.scos.store.memory;

import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 메모리 참조 구현. */
public final class MemorySpaceMeta implements SpaceMeta {
  private final Map<SpaceId, Map<String, String>> values = new HashMap<>();

  @Override
  public synchronized Optional<String> get(SpaceId space, String key) {
    return Optional.ofNullable(values.getOrDefault(space, Map.of()).get(key));
  }

  @Override
  public synchronized void set(SpaceId space, String key, String value) {
    values.computeIfAbsent(space, s -> new HashMap<>()).put(key, value);
  }
}
