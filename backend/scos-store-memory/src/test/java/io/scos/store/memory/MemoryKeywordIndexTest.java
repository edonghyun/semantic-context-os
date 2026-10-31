package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.KeywordIndexContract;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SearchFilter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("키워드 색인 계약 — 메모리 포트(CONTRACTS B3)")
class MemoryKeywordIndexTest extends KeywordIndexContract {
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
  protected KeywordIndex newIndex() {
    return new MemoryKeywordIndex(acl);
  }

  @Test
  @DisplayName("기본 생성자의 빈 ACL은 제한 검색에서 문서를 숨긴다")
  void defaultIndexHidesRestrictedDocuments() {
    // given
    var emptyAclIndex = new MemoryKeywordIndex();
    emptyAclIndex.upsert(A, List.of(new IndexedText("unit", "doc", "정산", "정산 정책", "/", false)));

    // when
    var got = emptyAclIndex.search(A, "정산", 1, ALICE);

    // then
    assertThat(got).isEmpty();
  }
}
