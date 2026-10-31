package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.VectorIndex;
import io.scos.spi.store.VectorIndexContract;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("벡터 색인 계약 — 메모리 포트(CONTRACTS B4)")
class MemoryVectorIndexTest extends VectorIndexContract {
  private static final SearchFilter ALICE =
      new SearchFilter(
          false,
          null,
          new AccessScope.Restricted(
              new Principal("alice"), Set.of(AccessKey.parse("user:slack-a:U1"))));

  private final MemoryAclStore acl = new MemoryAclStore();

  @Override
  protected AclStore aclStore() {
    return acl;
  }

  @Override
  protected VectorIndex newIndex() {
    return new MemoryVectorIndex(acl);
  }

  @Test
  @DisplayName("기본 생성자의 빈 ACL은 제한 검색에서 문서를 숨긴다")
  void defaultIndexHidesRestrictedDocuments() {
    // given
    var emptyAclIndex = new MemoryVectorIndex();
    emptyAclIndex.upsert(
        A, List.of(new IndexedUnit("unit", "doc", "/", false, new double[] {1, 0})));

    // when
    var got = emptyAclIndex.search(A, List.of(new double[] {1, 0}), 1, ALICE);

    // then
    assertThat(got).containsExactly(List.of());
  }
}
