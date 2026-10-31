package io.scos.engine.internal.access;

import io.scos.engine.AccessMode;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;

/** 명시적으로 선택한 단일 사용자 엔진의 범위. principal은 보존한다. */
public final class UnrestrictedScopes implements AccessScopes {
  @Override
  public AccessScope resolve(Principal principal) {
    return new AccessScope.Unrestricted(principal);
  }

  @Override
  public AccessMode mode() {
    return AccessMode.UNRESTRICTED;
  }
}
