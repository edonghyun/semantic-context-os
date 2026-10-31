package io.scos.spi.store;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 키워드 접근 계약의 문서·ACL 연결과 검색 준비를 소유한다. */
final class KeywordAccessScenario {
  private static final AccessKey K1 = AccessKey.parse("user:slack-a:U1");
  private static final AccessKey K2 = AccessKey.parse("user:slack-a:U2");
  private static final AccessKey SCOPE = AccessKey.parse("scope:notion-a:*");
  private static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
  private static final int DOCUMENT_COUNT = 6;
  private static final int TOP_K = 10;
  private static final int BLOCKED_REPETITIONS = 20;
  static final AccessScope ALICE = new AccessScope.Restricted(new Principal("alice"), Set.of(K1));
  static final AccessScope ORG = new AccessScope.Restricted(new Principal("alice"), Set.of(SCOPE));
  static final AccessScope NO_KEYS = new AccessScope.Restricted(new Principal("alice"), Set.of());
  private final KeywordIndex index;
  private final AclStore acls;
  private final SpaceId space;
  private final SpaceId otherSpace;

  KeywordAccessScenario(KeywordIndex index, AclStore acls, SpaceId space, SpaceId otherSpace) {
    this.index = index;
    this.acls = acls;
    this.space = space;
    this.otherSpace = otherSpace;
  }

  void givenDocuments() {
    index.upsert(
        space, List.of(text("d1"), text("d2"), text("d3"), text("d4"), text("d5"), text("d6")));
    acls.put(
        space,
        List.of(
            acl("d1", Status.OK, K1),
            acl("d2", Status.OK, K2),
            acl("d3", Status.UNKNOWN, K1),
            acl("d4", Status.FAILED, K1),
            acl("d5", Status.UNSUPPORTED, SCOPE)));
  }

  void requireSearchableDocuments() {
    if (search(AccessScope.singleUser()).size() != DOCUMENT_COUNT)
      throw new IllegalStateException("given 준비 실패: 여섯 문서가 모두 검색돼야 한다");
  }

  void givenHigherScoringBlockedDocument() {
    index.upsert(
        space,
        List.of(new IndexedText("d2", "d2", "", "정산 ".repeat(BLOCKED_REPETITIONS), "/", false)));
  }

  void requireBlockedDocumentRanksFirst() {
    if (!search(AccessScope.singleUser(), 1).equals(List.of("d2")))
      throw new IllegalStateException("given 준비 실패: 막힌 문서가 무제한 top-1이어야 한다");
  }

  void givenAclOnlyInOtherSpace() {
    acls.delete(space, List.of("d1"));
    acls.put(otherSpace, List.of(acl("d1", Status.OK, K1)));
  }

  List<String> search(AccessScope scope) {
    return search(scope, TOP_K);
  }

  List<String> search(AccessScope scope, int k) {
    return index.search(space, "정산", k, new SearchFilter(false, null, scope)).stream()
        .map(ScoredRef::documentId)
        .toList();
  }

  private IndexedText text(String id) {
    return new IndexedText(id, id, "", "정산 정책", "/", false);
  }

  private DocumentAcl acl(String id, Status status, AccessKey key) {
    return new DocumentAcl(id, status, Set.of(key), OBSERVED);
  }
}
