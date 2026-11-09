package io.scos.collection.internal.memory;

import io.scos.collection.internal.SourceConfigurationChangedException;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryIdentityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 메모리 참조 구현. */
public final class MemorySourceInstanceStore implements SourceInstanceStore {
  private final MemoryIdentityStore identities;

  public MemorySourceInstanceStore() {
    this(new MemoryIdentityStore());
  }

  public MemorySourceInstanceStore(MemoryIdentityStore identities) {
    this.identities = identities;
  }

  private final Map<String, SourceInstance> byId = new TreeMap<>();

  private final Map<SourceInstanceId, Integer> skipped = new HashMap<>();

  @Override
  public void recordOwnershipSkipped(SourceInstanceId source, int count) {
    identities.withDirectoryLock(
        () -> {
          if (byId.containsKey(source.value())) skipped.put(source, count);
        });
  }

  @Override
  public int ownershipSkipped(SourceInstanceId source) {
    return identities.withDirectoryLock(
        () -> {
          return skipped.getOrDefault(source, 0);
        });
  }

  @Override
  public void save(SourceInstance instance) {
    identities.withDirectoryLock(
        () -> {
          byId.put(instance.id().value(), instance);
          identities.registerSource(new SourceAccount(instance.id(), instance.kind()));
        });
  }

  @Override
  public Optional<SourceInstance> get(SourceInstanceId id) {
    return identities.withDirectoryLock(
        () -> {
          return Optional.ofNullable(byId.get(id.value()));
        });
  }

  @Override
  public void requireCollectionConfiguration(SourceDescriptor expected) {
    identities.withDirectoryLock(
        () -> {
          get(expected.id())
              .orElseThrow(SourceConfigurationChangedException::new)
              .requireCollectionConfiguration(expected);
        });
  }

  @Override
  public List<SourceInstance> listAll() {
    return identities.withDirectoryLock(
        () -> {
          return List.copyOf(byId.values());
        });
  }

  @Override
  public List<SourceInstance> listEnabled() {
    return identities.withDirectoryLock(
        () -> {
          return byId.values().stream().filter(SourceInstance::enabled).toList();
        });
  }
}
