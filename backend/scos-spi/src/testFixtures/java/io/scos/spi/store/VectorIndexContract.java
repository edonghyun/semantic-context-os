package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 벡터 색인 포트 계약(CONTRACTS B4). */
@DisplayName("벡터 색인은 코사인 점수 > 0인 유닛을 점수·적재 순서로 고른다(CONTRACTS B4)")
public abstract class VectorIndexContract {
  private static final SearchFilter ACTIVE =
      new SearchFilter(false, null, AccessScope.singleUser());
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  static final SearchFilter ALL = new SearchFilter(true, null, AccessScope.singleUser());

  /** 질의 벡터. 배열은 가변이라 공유 상수 대신 부를 때마다 새로 만든다(TESTING §3.3). */
  static double[] east() {
    return new double[] {1, 0};
  }

  static double[] north() {
    return new double[] {0, 1};
  }

  static double[] diagonal() {
    return new double[] {1, 1};
  }

  static final double EXACT = 1e-12;

  /** 근사·float4 어댑터의 허용 오차. */
  static final double FLOAT4 = 1e-6;

  static final int TOP_K = 10;
  static final AccessScope RESTRICTED =
      new AccessScope.Restricted(
          new Principal("alice"), Set.of(AccessKey.parse("user:slack-a:U1")));

  protected VectorIndex index;

  protected abstract VectorIndex newIndex();

  protected abstract AclStore aclStore();

  @BeforeEach
  void setUpContract() {
    index = newIndex();
  }

  static IndexedUnit unit(String id, String doc, double... v) {
    return new IndexedUnit(id, doc, "/knowledge", false, v);
  }

  static IndexedUnit inPartition(String partition, IndexedUnit u) {
    return new IndexedUnit(u.unitId(), u.documentId(), partition, u.archived(), u.embedding());
  }

  static IndexedUnit archived(IndexedUnit u) {
    return new IndexedUnit(u.unitId(), u.documentId(), u.partition(), true, u.embedding());
  }

  List<ScoredRef> search(SpaceId space, double[] query, int k, SearchFilter filter) {
    return index.search(space, List.of(query), k, filter).get(0);
  }

  static List<String> ids(List<ScoredRef> hits) {
    return hits.stream().map(ScoredRef::unitId).toList();
  }

  @Test
  @DisplayName("정규화하지 않은 벡터와 질의도 코사인 점수로 비교한다")
  void normalizesNonUnitVectors() {
    // given
    index.upsert(A, List.of(unit("scaled", "doc-scaled", 3, 4)));
    // when
    List<ScoredRef> hits = search(A, new double[] {6, 0}, TOP_K, ALL);
    // then
    assertThat(hits.getFirst().score()).isCloseTo(0.6, within(FLOAT4));
  }

  @Nested
  @DisplayName("시드 벡터의 문서마다 접근 ACL이 있을 때")
  class SeedAccess {
    static final List<String> SEED_IDS = List.of("d6", "d2", "d1", "d3", "d4", "d5");
    VectorAccessScenario access;

    @BeforeEach
    void givenSeedDocuments() {
      access = new VectorAccessScenario(index, aclStore(), A);
      access.givenDocuments();
      access.requireSearchableDocuments();
    }

    @Test
    @DisplayName("제한 vectorsOf는 허용 문서의 시드 벡터만 반환한다")
    void returnsVisibleSeedVectors() {
      // when
      var got = index.vectorsOf(A, VectorAccessScenario.ALICE, SEED_IDS);

      // then
      assertThat(got).containsOnlyKeys("d1");
    }

    @Test
    @DisplayName("빈 키의 vectorsOf는 비어 있다")
    void noKeysReturnNoVectors() {
      // when
      var got = index.vectorsOf(A, VectorAccessScenario.NO_KEYS, SEED_IDS);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("vectorsOf는 다른 공간의 ACL을 쓰지 않는다")
    void ignoresAclInOtherSpace() {
      access.givenAclOnlyInOtherSpace(B);

      // when
      var got = index.vectorsOf(A, VectorAccessScenario.ALICE, SEED_IDS);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("UNSUPPORTED 시드 벡터는 scope 키로 읽는다")
    void returnsUnsupportedSeedVector() {
      // when
      var got = index.vectorsOf(A, VectorAccessScenario.ORG, SEED_IDS);

      // then
      assertThat(got).containsOnlyKeys("d5");
    }

    @Test
    @DisplayName("무제한 vectorsOf는 ACL과 관계없이 기존 벡터를 읽는다")
    void unrestrictedReadsEverySeedVector() {
      // when
      var got = index.vectorsOf(A, AccessScope.singleUser(), SEED_IDS);

      // then
      assertThat(got).containsOnlyKeys("d1", "d2", "d3", "d4", "d5", "d6");
    }

    @Test
    @DisplayName("시드 접근 판정은 유닛 id가 아닌 문서 id를 쓴다")
    void usesDocumentIdForAcl() {
      index.upsert(A, List.of(unit("part", "d1", east())));

      // when
      var got = index.vectorsOf(A, VectorAccessScenario.ALICE, List.of("part"));

      // then
      assertThat(got).containsOnlyKeys("part");
    }
  }

  @Nested
  @DisplayName("문서마다 ACL이 있을 때")
  class DocumentAccess {
    VectorAccessScenario access;

    @BeforeEach
    void givenDocumentsWithAcl() {
      access = new VectorAccessScenario(index, aclStore(), A);
      access.givenDocuments();
      access.requireSearchableDocuments();
    }

    @Test
    @DisplayName("제한 범위는 키가 겹치는 OK 문서만 찾는다")
    void findsOnlyMatchingOkDocument() {
      // when
      List<String> got = access.search(VectorAccessScenario.ALICE);

      // then
      assertThat(got).containsExactly("d1");
    }

    @Test
    @DisplayName("UNKNOWN·FAILED 문서는 키가 겹쳐도 찾지 않는다")
    void excludesUnknownAndFailedDocuments() {
      // when
      List<String> got = access.search(VectorAccessScenario.ALICE);

      // then
      assertThat(got).contains("d1").doesNotContain("d3", "d4");
    }

    @Test
    @DisplayName("ACL 행이 없는 문서는 찾지 않는다")
    void excludesDocumentWithoutAcl() {
      // when
      List<String> got = access.search(VectorAccessScenario.ALICE);

      // then
      assertThat(got).contains("d1").doesNotContain("d6");
    }

    @Test
    @DisplayName("UNSUPPORTED 문서는 scope 키로 찾는다")
    void findsUnsupportedWithScopeKey() {
      // when
      List<String> got = access.search(VectorAccessScenario.ORG);

      // then
      assertThat(got).containsExactly("d5");
    }

    @Test
    @DisplayName("빈 키의 제한 범위는 아무것도 찾지 않는다")
    void emptyKeysFindNothing() {
      // when
      List<String> got = access.search(VectorAccessScenario.NO_KEYS);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("필터는 top-k 이전이라 막힌 문서가 더 가까워도 보이는 문서를 찾는다")
    void filtersBeforeTopK() {
      // given
      access.givenCloserBlockedDocument();
      access.requireBlockedDocumentRanksFirst();

      // when
      List<String> got = access.search(VectorAccessScenario.ALICE, 1);

      // then
      assertThat(got).containsExactly("d1");
    }

    @Test
    @DisplayName("제한 범위의 보관 제외는 top-k 이전에 적용된다")
    void excludesArchivedBeforeTopK() {
      // given
      access.givenCloserArchivedDocument();
      access.requireArchivedDocumentRanksFirst();

      // when
      List<String> got = access.search(VectorAccessScenario.ALICE, 1);

      // then
      assertThat(got).containsExactly("d1");
    }

    @Test
    @DisplayName("제한 범위의 파티션 필터는 top-k 이전에 적용된다")
    void filtersPartitionBeforeTopK() {
      // given
      access.givenCloserDocumentInOtherPartition();
      access.requireOtherPartitionRanksFirst();

      // when
      List<String> got = access.searchFirstInPartition(VectorAccessScenario.ALICE, "/");

      // then
      assertThat(got).containsExactly("d1");
    }

    @Test
    @DisplayName("k=50은 필터 뒤 기준이라 더 가까운 막힌 문서가 있어도 50건을 채운다")
    void fillsRelatedLimitAfterFiltering() {
      // given
      access.givenRelatedCandidates();
      access.requireBlockedDocumentRanksFirst();

      // when
      List<String> got =
          access.search(VectorAccessScenario.ALICE, VectorAccessScenario.RELATED_LIMIT);

      // then
      assertThat(got).containsExactlyElementsOf(access.relatedDocumentIds());
    }

    @Test
    @DisplayName("무제한 범위는 ACL과 관계없이 모든 문서를 찾는다")
    void unrestrictedIgnoresAcls() {
      // when
      List<String> got = access.search(AccessScope.singleUser());

      // then
      assertThat(got).containsExactly("d1", "d2", "d3", "d4", "d5", "d6");
    }

    @Test
    @DisplayName("다른 공간의 같은 문서 id ACL은 쓰이지 않는다")
    void ignoresAclInOtherSpace() {
      // given
      access.givenAclOnlyInOtherSpace(B);

      // when
      List<String> got = access.search(VectorAccessScenario.ALICE);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("여러 질의는 각각 접근 가능한 문서만 돌려준다")
    void filtersEveryQuery() {
      // when
      var got = access.searchEach(VectorAccessScenario.ALICE, east(), north());

      // then
      assertThat(got).containsExactly(List.of("d1"), List.of("d1"));
    }
  }

  @Nested
  @DisplayName("동쪽 x(1)·동북 y(cos 0.6)·서쪽 z(-1)가 있을 때")
  class ThreeDirections {
    @BeforeEach
    void givenThreeDirections() {
      index.upsert(
          A, List.of(unit("x", "d1", 1, 0), unit("y", "d2", 0.6, 0.8), unit("z", "d3", -1, 0)));
    }

    @Test
    @DisplayName("ACL 행이 없는 문서는 제한 검색의 각 질의에서 나오지 않는다")
    void restrictedQueriesExcludeDocumentsWithoutAcl() {
      // given
      if (search(A, east(), TOP_K, ALL).isEmpty())
        throw new IllegalStateException("given 준비 실패: 무제한 벡터 검색에는 문서가 있어야 한다");

      // when
      List<List<ScoredRef>> got =
          index.search(
              A, List.of(east(), north()), TOP_K, new SearchFilter(false, null, RESTRICTED));

      // then
      assertThat(got).containsExactly(List.of(), List.of());
    }

    @Test
    @DisplayName("점수 > 0인 유닛만 점수 내림차순으로 돌려준다")
    void positiveScoresDescending() {
      // when
      List<ScoredRef> hits = search(A, east(), TOP_K, ACTIVE);

      // then
      assertThat(ids(hits)).containsExactly("x", "y");
    }

    @Test
    @DisplayName("점수는 코사인 유사도다(동일 벡터 1)")
    void scoreIsCosine() {
      // when
      List<ScoredRef> hits = search(A, east(), TOP_K, ACTIVE);

      // then
      assertThat(hits.get(0).score()).isCloseTo(1.0, within(EXACT));
      assertThat(hits.get(1).score()).isCloseTo(0.6, within(FLOAT4));
    }

    @Test
    @DisplayName("질의 여러 개는 질의별 결과를 돌려준다")
    void searchesEachQuery() {
      // when
      List<List<ScoredRef>> results = index.search(A, List.of(east(), north()), TOP_K, ACTIVE);

      // then
      assertThat(ids(results.get(1))).containsExactly("y");
    }

    @Test
    @DisplayName("빈 배치 upsert는 색인을 바꾸지 않는다")
    void emptyBatchChangesNothing() {
      // when
      index.upsert(A, List.of());

      // then
      assertThat(ids(search(A, east(), TOP_K, ACTIVE))).containsExactly("x", "y");
    }
  }

  @Test
  @DisplayName("영벡터 질의는 빈 결과다")
  void zeroQueryIsEmpty() {
    // given
    index.upsert(A, List.of(unit("x", "d1", 1, 0)));

    // when
    List<List<ScoredRef>> results = index.search(A, List.of(new double[] {0, 0}), TOP_K, ACTIVE);

    // then
    assertThat(results).containsExactly(List.of());
  }

  @Nested
  @DisplayName("같은 벡터 둘, 3차원 벡터, 영벡터가 있을 때")
  class TiesAndUnusableVectors {
    @BeforeEach
    void givenTiesAndUnusableVectors() {
      index.upsert(
          A,
          List.of(
              unit("z-first", "d1", 1, 1),
              unit("a-second", "d2", 1, 1),
              unit("three", "d3", 1, 1, 1),
              unit("zero", "d4", 0, 0)));
    }

    @Test
    @DisplayName("동점이면 먼저 적재한 유닛이 앞선다")
    void tiesByLoadOrder() {
      // when
      List<String> got = ids(search(A, diagonal(), TOP_K, ACTIVE));

      // then
      assertThat(got).containsExactly("z-first", "a-second");
    }

    @Test
    @DisplayName("길이가 다른 벡터와 영벡터 행은 검색되지 않는다")
    void unusableVectorsAreExcluded() {
      // when
      List<String> got = ids(search(A, diagonal(), TOP_K, ACTIVE));

      // then: 쓸 수 있는 벡터는 검색된다(대조군)
      assertThat(got).contains("z-first", "a-second").doesNotContain("three", "zero");
    }

    @Test
    @DisplayName("차원이 다르거나 영벡터인 행도 저장은 된다")
    void unusableVectorsAreStored() {
      // when
      Map<String, double[]> got =
          index.vectorsOf(A, AccessScope.singleUser(), List.of("three", "zero"));

      // then
      assertThat(got.keySet()).containsExactlyInAnyOrder("three", "zero");
    }
  }

  @Nested
  @DisplayName("같은 방향의 raw(/knowledge)·archived·/rules 유닛이 있을 때")
  class FilterCandidates {
    @BeforeEach
    void givenFilterCandidates() {
      index.upsert(
          A,
          List.of(
              unit("raw", "d1", 1, 0),
              archived(unit("arch", "d2", east())),
              inPartition("/rules", unit("rules", "d3", east()))));
    }

    @Test
    @DisplayName("archived 제외는 top-k 이전에 적용된다")
    void archivedFilterBeforeTopK() {
      // when
      List<ScoredRef> hits = search(A, east(), 1, ACTIVE);

      // then
      assertThat(ids(hits)).containsExactly("raw");
    }

    @Test
    @DisplayName("archived 포함 필터는 모든 유닛을 적재 순서로 돌려준다")
    void includeArchived() {
      // when
      List<ScoredRef> hits = search(A, east(), 3, ALL);

      // then
      assertThat(ids(hits)).containsExactly("raw", "arch", "rules");
    }

    @Test
    @DisplayName("partition 필터는 top-k 이전에 적용된다")
    void partitionFilterBeforeTopK() {
      // when
      List<ScoredRef> hits =
          search(A, east(), 1, new SearchFilter(true, List.of("/rules"), AccessScope.singleUser()));

      // then
      assertThat(ids(hits)).containsExactly("rules");
    }
  }

  @Test
  @DisplayName("같은 unitId를 다시 넣으면 새 벡터로 검색된다")
  void upsertReplacesVector() {
    // given
    index.upsert(A, List.of(unit("x", "d1", 1, 0), unit("y", "d2", 1, 0)));

    // when
    index.upsert(A, List.of(unit("x", "d1", 0.9, 0.1)));

    // then: y가 더 가깝다
    assertThat(ids(search(A, east(), TOP_K, ACTIVE))).containsExactly("y", "x");
  }

  @Test
  @DisplayName("한 배치에 같은 unitId가 다시 나와도 동점 순서는 처음 자리다")
  void repeatedUnitInBatchKeepsFirstLoadOrder() {
    // given: 한 배치에서 x(서쪽) → y(동쪽) → x(동쪽, y와 같은 벡터) 순서로 넣는다
    index.upsert(A, List.of(unit("x", "d1", -1, 0), unit("y", "d2", 1, 0), unit("x", "d1", 1, 0)));

    // when
    List<ScoredRef> hits = search(A, east(), TOP_K, ACTIVE);

    // then
    assertThat(ids(hits)).containsExactly("x", "y");
  }

  @Test
  @DisplayName("삭제한 유닛은 결과에서 사라진다")
  void deleteRemoves() {
    // given
    index.upsert(A, List.of(unit("x", "d1", 0.9, 0.1), unit("y", "d2", 1, 0)));

    // when
    index.delete(A, List.of("y"));

    // then
    assertThat(ids(search(A, east(), TOP_K, ACTIVE))).containsExactly("x");
  }

  @Test
  @DisplayName("한 공간에서 삭제해도 다른 공간의 같은 unitId는 남는다")
  void deleteKeepsSameUnitIdInAnotherSpace() {
    // given
    index.upsert(A, List.of(unit("x", "d1", 1, 0)));
    index.upsert(B, List.of(unit("x", "d1", 1, 0)));

    // when
    index.delete(A, List.of("x"));

    // then
    assertThat(ids(search(B, east(), TOP_K, ACTIVE))).containsExactly("x");
  }

  @Nested
  @DisplayName("A에 x·y, B에 z를 넣었을 때")
  class VectorsInTwoSpaces {
    @BeforeEach
    void givenVectorsInTwoSpaces() {
      index.upsert(A, List.of(unit("x", "d1", 0.5, 0.25), unit("y", "d2", 1, 0)));
      index.upsert(B, List.of(unit("z", "d1", 1, 1)));
    }

    @Test
    @DisplayName("vectorsOf는 그 공간에 저장된 벡터만 id별로 돌려준다")
    void vectorsOf() {
      // when
      Map<String, double[]> got =
          index.vectorsOf(A, AccessScope.singleUser(), List.of("y", "nope", "x", "z"));

      // then
      assertThat(got.keySet()).containsExactlyInAnyOrder("x", "y");
      assertThat(got.get("x")).containsExactly(0.5, 0.25);
    }

    @Test
    @DisplayName("B 공간의 벡터는 B로 읽는다")
    void vectorsOfOtherSpace() {
      // when
      Map<String, double[]> got = index.vectorsOf(B, AccessScope.singleUser(), List.of("z"));

      // then
      assertThat(got.get("z")).containsExactly(1.0, 1.0);
    }
  }

  @Test
  @DisplayName("다른 공간의 유닛은 보이지 않는다")
  void spaceIsolation() {
    // given
    index.upsert(A, List.of(unit("mine", "d1", 1, 0)));
    index.upsert(B, List.of(unit("theirs", "d1", 1, 0)));

    // when
    List<String> inA = ids(search(A, east(), TOP_K, ALL));
    List<String> inB = ids(search(B, east(), TOP_K, ALL));

    // then
    assertThat(inA).containsExactly("mine");
    assertThat(inB).containsExactly("theirs");
  }
}
