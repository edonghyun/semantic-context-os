package io.scos.collection.internal.memory;

import io.scos.collection.internal.SourceConfigurationChangedException;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 메모리 참조 구현. */
public final class MemorySourceInstanceStore implements SourceInstanceStore {
  private final Map<String, SourceInstance> byId = new TreeMap<>();

  private final Map<SourceInstanceId, Integer> skipped = new HashMap<>();

  @Override
  public synchronized void recordOwnershipSkipped(SourceInstanceId source, int count) {
    if (byId.containsKey(source.value())) skipped.put(source, count);
  }

  @Override
  public synchronized int ownershipSkipped(SourceInstanceId source) {
    return skipped.getOrDefault(source, 0);
  }

  @Override
  public synchronized void save(SourceInstance instance) {
    byId.put(instance.id().value(), instance);
  }

  @Override
  public synchronized Optional<SourceInstance> get(SourceInstanceId id) {
    return Optional.ofNullable(byId.get(id.value()));
  }

  @Override
  public synchronized void requireCollectionConfiguration(SourceDescriptor expected) {
    get(expected.id())
        .orElseThrow(SourceConfigurationChangedException::new)
        .requireCollectionConfiguration(expected);
  }

  @Override
  public synchronized List<SourceInstance> listAll() {
    return List.copyOf(byId.values());
  }

  @Override
  public synchronized List<SourceInstance> listEnabled() {
    return byId.values().stream().filter(SourceInstance::enabled).toList();
  }
}
