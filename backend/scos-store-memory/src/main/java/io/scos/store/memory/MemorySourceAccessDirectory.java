package io.scos.store.memory;

import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.Map;

/** 읽기 신원 저장소와 같은 확정 그룹·신원 원장. run별 관측은 수집 저장소가 소유한다. */
public final class MemorySourceAccessDirectory implements SourceAccessDirectory {
  private final MemoryIdentityStore reader;
  private final Map<SourceInstanceId, Snapshot> sources = new HashMap<>();

  public MemorySourceAccessDirectory(MemoryIdentityStore reader) {
    this.reader = reader;
  }

  @Override
  public synchronized Snapshot current(SourceInstanceId source) {
    return sources.getOrDefault(source, Snapshot.empty());
  }

  @Override
  public synchronized void save(SourceInstanceId source, Snapshot snapshot) {
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
                        source, identity.sourceUserId(), identity.guest(), identity.deleted()))
            .toList(),
        next.groups().values().stream()
            .map(group -> new MemoryIdentityStore.Group(source, group.groupId(), group.members()))
            .toList());
    sources.put(source, next);
  }
}
