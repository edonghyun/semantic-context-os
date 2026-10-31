package io.scos.spi.store;

import io.scos.spi.access.AccessKey;
import java.util.Objects;
import java.util.Set;

/** 검색·읽기의 명시적 접근 범위(접근 설계 §4.1). */
public sealed interface AccessScope permits AccessScope.Unrestricted, AccessScope.Restricted {
  Principal principal();

  /** 모든 문서를 볼 수 있는 범위. 단일 사용자 모드·운영자 출구 전용이다. */
  record Unrestricted(Principal principal) implements AccessScope {
    public Unrestricted {
      Objects.requireNonNull(principal, "principal");
    }
  }

  /** 빈 키 집합은 아무것도 볼 수 없는 상태다. */
  record Restricted(Principal principal, Set<AccessKey> keys) implements AccessScope {
    public Restricted {
      Objects.requireNonNull(principal, "principal");
      keys = Set.copyOf(keys);
    }
  }

  /** 단일 사용자 모드의 범위. */
  static AccessScope singleUser() {
    return new Unrestricted(Principal.singleUser());
  }
}
