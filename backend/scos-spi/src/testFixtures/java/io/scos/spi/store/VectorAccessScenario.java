package io.scos.spi.store;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** 벡터 접근 계약의 문서·ACL 연결과 검색 준비를 소유한다. */
final class VectorAccessScenario {
  private static final AccessKey K1 = AccessKey.parse("user:slack-a:U1");
  private static final AccessKey K2 = AccessKey.parse("user:slack-a:U2");
  private static final AccessKey SCOPE = AccessKey.parse("scope:notion-a:*");
  private static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
  private static final int DOCUMENT_COUNT = 6;
  private static final int TOP_K = 10;
  static final int RELATED_LIMIT = 50;
  static final AccessScope ALICE = new AccessScope.Restricted(new Principal("alice"), Set.of(K1));
  static final AccessScope ORG = new AccessScope.Restricted(new Principal("alice"), Set.of(SCOPE));
  static final AccessScope NO_KEYS = new AccessScope.Restricted(new Principal("alice"), Set.of());
  private final VectorIndex index;
  private final AclStore acls;
  private final SpaceId space;

  VectorAccessScenario(VectorIndex index, AclStore acls, SpaceId space) {
    this.index = index;
    this.acls = acls;
    this.space = space;
  }

  void givenDocuments() {
    index.upsert(
        space, List.of(unit("d1"), unit("d2"), unit("d3"), unit("d4"), unit("d5"), unit("d6")));
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

  List<String> search(AccessScope scope) {
    return search(scope, TOP_K);
  }

  List<String> search(AccessScope scope, int k) {
    return search(new SearchFilter(false, null, scope), k);
  }

  private List<String> search(SearchFilter filter, int k) {
    return index.search(space, List.of(new double[] {1, 0}), k, filter).get(0).stream()
        .map(ScoredRef::documentId)
        .toList();
  }

  void givenCloserArchivedDocument() {
    index.upsert(
        space, List.of(new IndexedUnit("archived", "archived", "/", true, new double[] {1, 0})));
    acls.put(space, List.of(acl("archived", Status.OK, K1)));
  }

  void requireArchivedDocumentRanksFirst() {
    if (!search(new SearchFilter(true, null, ALICE), 1).equals(List.of("archived")))
      throw new IllegalStateException("given 준비 실패: 보관 포함 제한 검색의 top-1은 보관 문서여야 한다");
  }

  void givenCloserDocumentInOtherPartition() {
    index.upsert(
        space, List.of(new IndexedUnit("rules", "rules", "/rules", false, new double[] {1, 0})));
    acls.put(space, List.of(acl("rules", Status.OK, K1)));
  }

  void requireOtherPartitionRanksFirst() {
    if (!search(ALICE, 1).equals(List.of("rules")))
      throw new IllegalStateException("given 준비 실패: 파티션 미지정 제한 검색의 top-1은 다른 파티션이어야 한다");
  }

  List<String> searchFirstInPartition(AccessScope scope, String partition) {
    return search(new SearchFilter(false, List.of(partition), scope), 1);
  }

  List<List<String>> searchEach(AccessScope scope, double[]... queries) {
    return index
        .search(space, List.of(queries), TOP_K, new SearchFilter(false, null, scope))
        .stream()
        .map(hits -> hits.stream().map(ScoredRef::documentId).toList())
        .toList();
  }

  void givenCloserBlockedDocument() {
    index.upsert(space, List.of(new IndexedUnit("d2", "d2", "/", false, new double[] {1, 0})));
  }

  void requireBlockedDocumentRanksFirst() {
    if (!search(AccessScope.singleUser(), 1).equals(List.of("d2")))
      throw new IllegalStateException("given 준비 실패: 막힌 문서가 무제한 top-1이어야 한다");
  }

  void givenRelatedCandidates() {
    index.delete(space, List.of("d1", "d2", "d3", "d4", "d5", "d6"));
    givenCloserBlockedDocument();
    index.upsert(space, relatedDocumentIds().stream().map(this::unit).toList());
    acls.put(space, relatedDocumentIds().stream().map(id -> acl(id, Status.OK, K1)).toList());
  }

  List<String> relatedDocumentIds() {
    return IntStream.range(0, RELATED_LIMIT).mapToObj(i -> "visible-" + i).toList();
  }

  void givenAclOnlyInOtherSpace(SpaceId otherSpace) {
    acls.delete(space, List.of("d1"));
    acls.put(otherSpace, List.of(acl("d1", Status.OK, K1)));
  }

  private IndexedUnit unit(String id) {
    return new IndexedUnit(id, id, "/", false, new double[] {0.6, 0.8});
  }

  private DocumentAcl acl(String id, Status status, AccessKey key) {
    return new DocumentAcl(id, status, Set.of(key), OBSERVED);
  }
}
