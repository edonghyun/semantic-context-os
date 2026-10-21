package io.scos.collection.internal;

import io.scos.engine.support.projection.WriteGuardException;

/** 점유가 만료됐거나 다른 owner·token에게 넘어갔다. 호출자는 run을 종결해야 하며 재시도하지 않는다. */
public class ClaimLostException extends WriteGuardException {
  public ClaimLostException(String message) {
    super(message);
  }
}
