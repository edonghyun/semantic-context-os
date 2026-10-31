package io.scos.engine.internal.access;

import io.scos.engine.support.PureFunctions;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Tier;

/** 압축 원본의 ACL 교집합을 지원하기 전까지 제한 범위는 압축 유닛을 다루지 않는다. */
@PureFunctions
public final class CompactedUnits {
  private CompactedUnits() {}

  public static boolean enabled(AccessScope access) {
    return access instanceof AccessScope.Unrestricted;
  }

  public static boolean admits(AccessScope access, ContextUnit unit) {
    return enabled(access) || unit.tier() != Tier.COMPACTED;
  }
}
