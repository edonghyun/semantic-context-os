package io.scos.engine.admin.boundarysample;

import io.scos.engine.internal.admin.AutomaticIdentityLinks;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.store.Principal;

/** 공개 하위 패키지의 시그니처 누출을 확인하는 규칙 표본. */
public final class PublicSignatureSamples {
  private PublicSignatureSamples() {}

  public record LeaksInternal(AutomaticIdentityLinks links) {}

  public record LeaksSupport(MutableClock clock) {}

  public record PublicOnly(Principal principal) {}
}
