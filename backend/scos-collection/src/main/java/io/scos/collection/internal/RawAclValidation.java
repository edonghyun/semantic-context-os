package io.scos.collection.internal;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AccessModel;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.SourceDescriptor;
import java.util.Set;

/** collect와 collectAccess가 공유하는 원천 선언·키 검증. 위반은 관측 시각을 보존해 닫는다. */
public final class RawAclValidation {
  public RawAcl validate(SourceDescriptor source, AccessPolicy policy, RawAcl acl) {
    if (!(acl instanceof RawAcl.Observed observed)) return acl;
    if (observed.keys().stream().anyMatch(key -> !key.instance().equals(source.id())))
      return failed(observed);
    if (observed.status() == Status.UNSUPPORTED) {
      if (!policy.permitsScope(source)
          || observed.keys().size() != 1
          || observed.keys().stream().anyMatch(key -> key.kind() != AccessKey.Kind.SCOPE))
        return failed(observed);
      return observed;
    }
    if (observed.keys().stream().anyMatch(key -> key.kind() == AccessKey.Kind.SCOPE)
        || (policy.model() == AccessModel.UNSUPPORTED && observed.status() == Status.OK))
      return failed(observed);
    return switch (observed.status()) {
      case UNKNOWN -> RawAcl.observed(observed.status(), Set.of(), observed.observedAt());
      case FAILED -> RawAcl.failed(observed.reason(), observed.observedAt());
      case OK, UNSUPPORTED -> observed;
    };
  }

  private RawAcl.Observed failed(RawAcl.Observed observed) {
    return RawAcl.failed(AccessFailureReason.INVALID_CARRIER, observed.observedAt());
  }
}
