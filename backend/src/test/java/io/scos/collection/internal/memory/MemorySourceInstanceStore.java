package io.scos.collection.internal.memory;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 메모리 참조 구현. */
public final class MemorySourceInstanceStore implements SourceInstanceStore {
  private final Map<String, SourceInstance> byId = new TreeMap<>();

  @Override
  public synchronized void save(SourceInstance instance) {
    byId.put(instance.id().value(), instance);
  }

  @Override
  public synchronized Optional<SourceInstance> get(SourceInstanceId id) {
    return Optional.ofNullable(byId.get(id.value()));
  }

  @Override
  public synchronized List<SourceInstance> listEnabled() {
    return byId.values().stream().filter(SourceInstance::enabled).toList();
  }
}
