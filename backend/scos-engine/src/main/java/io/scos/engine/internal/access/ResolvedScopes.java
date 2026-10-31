package io.scos.engine.internal.access;

import io.scos.engine.AccessMode;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;

/** 제한 엔진은 요청마다 신원을 다시 읽는다. */
public final class ResolvedScopes implements AccessScopes {
  private final AccessResolver resolver;

  public ResolvedScopes(AccessResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public AccessScope resolve(Principal principal) {
    return resolver.resolve(principal);
  }

  @Override
  public AccessMode mode() {
    return AccessMode.RESTRICTED;
  }
}
