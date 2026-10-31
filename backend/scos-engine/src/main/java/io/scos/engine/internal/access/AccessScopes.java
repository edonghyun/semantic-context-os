package io.scos.engine.internal.access;

import io.scos.engine.AccessMode;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;

/** 엔진 모드에 따라 매 요청의 principal을 읽기 범위로 해석한다. */
public sealed interface AccessScopes permits UnrestrictedScopes, ResolvedScopes {
  AccessScope resolve(Principal principal);

  AccessMode mode();
}
