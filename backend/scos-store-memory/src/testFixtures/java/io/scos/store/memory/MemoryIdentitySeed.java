package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;

/** 공통 접근 디렉터리를 메모리 신원 저장소의 입력으로 변환한다. */
public final class MemoryIdentitySeed {
  private final MemoryIdentityStore memory;

  public MemoryIdentitySeed(MemoryIdentityStore memory) {
    this.memory = memory;
  }

  public void seed(AccessDirectory directory) {
    memory.replace(
        new MemoryIdentityStore.Directory(
            directory.users().stream()
                .map(u -> new MemoryIdentityStore.User(u.id(), u.email(), u.active()))
                .toList(),
            directory.identities().stream()
                .map(
                    i ->
                        new MemoryIdentityStore.Identity(
                            i.instance(), i.id(), i.guest(), i.deleted()))
                .toList(),
            directory.links().stream()
                .map(l -> new MemoryIdentityStore.Link(l.instance(), l.id(), l.userId()))
                .toList(),
            directory.groups().stream()
                .map(g -> new MemoryIdentityStore.Group(g.instance(), g.id(), g.members()))
                .toList(),
            directory.scopes()));
  }
}
