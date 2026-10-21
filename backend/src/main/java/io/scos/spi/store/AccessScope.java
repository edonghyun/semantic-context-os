package io.scos.spi.store;

import java.util.Objects;
import java.util.Set;

/**
 * 검색의 접근 범위(다중 사용자 접근 설계 §5): principal과 그가 볼 수 있는 접근 키 집합. {@link SearchFilter} 옆에서 검색 레시피로 내려가는
 * 자리다. 0.1.0은 접근 필터가 없어 키는 늘 비어 있고 색인에 내려가지 않는다(§8) — 0.2-a에서 후보 필터에 합친다.
 */
public record AccessScope(Principal principal, Set<String> accessKeys) {
  public AccessScope {
    Objects.requireNonNull(principal, "principal");
    accessKeys = Set.copyOf(accessKeys);
  }

  /** 접근 필터 없음(0.1.0): 키를 비워 둔다. */
  public static AccessScope unfiltered(Principal principal) {
    return new AccessScope(principal, Set.of());
  }

  /** 단일 사용자 모드의 범위. */
  public static AccessScope singleUser() {
    return unfiltered(Principal.singleUser());
  }
}
