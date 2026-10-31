package io.scos.spi.access;

import io.scos.spi.store.AccessScope;
import java.util.Objects;
import java.util.Optional;

/** 접근 범위와 ACL 부재를 함께 판정하는 문서 접근 정책. */
public record DocumentAccess(AccessScope scope) {
  public DocumentAccess {
    Objects.requireNonNull(scope, "scope");
  }

  public boolean admits(Optional<DocumentAcl> acl) {
    return switch (scope) {
      case AccessScope.Unrestricted ignored -> true;
      case AccessScope.Restricted restricted ->
          acl.map(value -> value.visibleTo(restricted.keys())).orElse(false);
    };
  }
}
