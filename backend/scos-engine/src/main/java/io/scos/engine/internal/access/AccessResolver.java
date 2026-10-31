package io.scos.engine.internal.access;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import java.util.Set;

/** 요청 시점의 신원 키를 읽어 제한 범위로 해석한다. 조회 장애는 호출자에게 전파한다. */
public final class AccessResolver {
  private final IdentityStore identities;
  private final DomainPolicy domains;

  public AccessResolver(IdentityStore identities, DomainPolicy domains) {
    this.identities = identities;
    this.domains = domains;
  }

  public AccessScope resolve(Principal principal) {
    return new AccessScope.Restricted(
        principal, identities.accessKeysOf(principal.userId(), domains).orElse(Set.of()));
  }
}
