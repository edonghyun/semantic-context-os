package io.scos.boot;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.store.UnitMaintenance;

/** 엔진 조립에 필요한 접근 포트. 제한 모드는 유지보수 포트를 받지 않는다. */
public sealed interface AccessParts {
  record Unrestricted(UnitMaintenance maintenance) implements AccessParts {}

  record Restricted(IdentityStore identities, SourceFreshness freshness, DomainPolicy domains)
      implements AccessParts {}
}
