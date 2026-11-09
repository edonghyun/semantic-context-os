package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import java.time.Instant;
import java.util.Locale;

/** 공통 접근 디렉터리를 메모리 신원 저장소의 입력으로 변환한다. */
public final class MemoryIdentitySeed {
  private final MemoryIdentityStore memory;

  public MemoryIdentitySeed(MemoryIdentityStore memory) {
    this.memory = memory;
  }

  public void seed(AccessDirectory directory) {
    memory.withDirectoryLock(
        () -> {
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
                  directory.links().stream().map(this::link).toList(),
                  directory.groups().stream()
                      .map(g -> new MemoryIdentityStore.Group(g.instance(), g.id(), g.members()))
                      .toList(),
                  directory.scopes().stream()
                      .map(source -> new ScopeDesignation(source, "fixture", Instant.EPOCH))
                      .toList(),
                  memory.snapshot().sources()));
          return true;
        });
  }

  private MemoryIdentityStore.Link link(AccessDirectory.Link link) {
    return new MemoryIdentityStore.Link(
        link.instance(),
        link.id(),
        link.userId(),
        LinkMethod.valueOf(link.method().toUpperCase(Locale.ROOT)),
        Instant.EPOCH,
        "fixture");
  }
}
