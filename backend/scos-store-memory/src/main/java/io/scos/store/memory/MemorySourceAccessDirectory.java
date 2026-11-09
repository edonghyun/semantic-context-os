package io.scos.store.memory;

import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 읽기 신원 저장소와 같은 확정 그룹·신원 원장. run별 관측은 수집 저장소가 소유한다. */
public final class MemorySourceAccessDirectory implements SourceAccessDirectory {
  private final MemoryIdentityStore reader;
  private final Map<SourceInstanceId, Snapshot> sources;

  public MemorySourceAccessDirectory(MemoryIdentityStore reader) {
    this.reader = reader;
    this.sources = reader.sourceAccessSnapshots();
  }

  /** current → merge → save 전체를 관리 트랜잭션과 직렬화한다. */
  public <T> T withLock(Supplier<T> work) {
    return reader.withDirectoryLock(work);
  }

  public void withLock(Runnable work) {
    reader.withDirectoryLock(work);
  }

  boolean belongsTo(MemoryIdentityStore identities) {
    return reader == identities;
  }

  Map<SourceInstanceId, Snapshot> snapshot() {
    return Map.copyOf(sources);
  }

  void restore(Map<SourceInstanceId, Snapshot> snapshot) {
    sources.clear();
    sources.putAll(snapshot);
  }

  @Override
  public Snapshot current(SourceInstanceId source) {
    return withLock(() -> sources.getOrDefault(source, Snapshot.empty()));
  }

  @Override
  public void save(SourceInstanceId source, Snapshot snapshot) {
    withLock(
        () -> {
          var old = current(source);
          var identities = new HashMap<>(old.identities());
          identities.putAll(snapshot.identities());
          var groups = new HashMap<>(old.groups());
          groups.putAll(snapshot.groups());
          var next = new Snapshot(identities, groups);
          reader.replaceSourceAccess(
              source,
              next.identities().values().stream()
                  .map(
                      identity ->
                          new MemoryIdentityStore.Identity(
                              source,
                              identity.sourceUserId(),
                              identity.guest(),
                              identity.deleted()))
                  .toList(),
              next.groups().values().stream()
                  .map(
                      group ->
                          new MemoryIdentityStore.Group(source, group.groupId(), group.members()))
                  .toList());
          sources.put(source, next);
        });
  }
}
