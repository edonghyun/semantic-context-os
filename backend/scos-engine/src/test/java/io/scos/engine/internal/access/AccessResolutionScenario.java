package io.scos.engine.internal.access;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryIdentityStore.Directory;
import io.scos.store.memory.MemoryIdentityStore.User;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 실제 메모리 어댑터와 요청자·도메인 정책을 조립하는 테스트 컨텍스트. */
final class AccessResolutionScenario {
  private static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
  private static final SourceInstanceId NOTION = new SourceInstanceId("notion-a");
  static final Principal MEMBER = new Principal("member");
  static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "example.com"));
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private final AccessResolver resolver = new AccessResolver(identities, DOMAINS);

  void registerMember() {
    identities.replace(
        directoryForUnlinkedUser(new User(MEMBER.userId(), "member@example.com", true)));
  }

  void deactivateAfterRequest() {
    resolver.resolve(MEMBER);
    identities.replace(
        directoryForUnlinkedUser(new User(MEMBER.userId(), "member@example.com", false)));
  }

  AccessScope resolve(Principal principal) {
    return resolver.resolve(principal);
  }

  private Directory directoryForUnlinkedUser(User user) {
    return new Directory(List.of(user), List.of(), List.of(), List.of(), Set.of(NOTION));
  }

  static AccessResolver unavailableResolver(RuntimeException failure) {
    IdentityStore unavailable =
        (id, domains) -> {
          throw failure;
        };
    return new AccessResolver(unavailable, DOMAINS);
  }
}
