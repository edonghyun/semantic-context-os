package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 벡터 색인 포트 계약(CONTRACTS B4, TS search-contract-testkit `vector` 블록의 Java 판). */
@DisplayName("벡터 색인은 코사인 점수 > 0인 유닛을 점수·적재 순서로 고른다(CONTRACTS B4)")
public abstract class VectorIndexContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  static final SearchFilter ALL = new SearchFilter(true, null);

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

  protected VectorIndex index;

  protected abstract VectorIndex newIndex();

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

  @Nested
  @DisplayName("동쪽 x(1)·동북 y(cos 0.6)·서쪽 z(-1)가 있을 때")
  class ThreeDirections {
    @BeforeEach
    void givenThreeDirections() {
      index.upsert(
          A, List.of(unit("x", "d1", 1, 0), unit("y", "d2", 0.6, 0.8), unit("z", "d3", -1, 0)));
    }

    @Test
    @DisplayName("점수 > 0인 유닛만 점수 내림차순으로 돌려준다")
    void positiveScoresDescending() {
      // when
      List<ScoredRef> hits = search(A, east(), TOP_K, SearchFilter.NONE);

      // then
      assertThat(ids(hits)).containsExactly("x", "y");
    }

    @Test
    @DisplayName("점수는 코사인 유사도다(동일 벡터 1)")
    void scoreIsCosine() {
      // when
      List<ScoredRef> hits = search(A, east(), TOP_K, SearchFilter.NONE);

      // then
      assertThat(hits.get(0).score()).isCloseTo(1.0, within(EXACT));
      assertThat(hits.get(1).score()).isCloseTo(0.6, within(FLOAT4));
    }

    @Test
    @DisplayName("질의 여러 개는 질의별 결과를 돌려준다")
    void searchesEachQuery() {
      // when
      List<List<ScoredRef>> results =
          index.search(A, List.of(east(), north()), TOP_K, SearchFilter.NONE);

      // then
      assertThat(ids(results.get(1))).containsExactly("y");
    }

    @Test
    @DisplayName("빈 배치 upsert는 색인을 바꾸지 않는다")
    void emptyBatchChangesNothing() {
      // when
      index.upsert(A, List.of());

      // then
      assertThat(ids(search(A, east(), TOP_K, SearchFilter.NONE))).containsExactly("x", "y");
    }
  }

  @Test
  @DisplayName("영벡터 질의는 빈 결과다")
  void zeroQueryIsEmpty() {
    // given
    index.upsert(A, List.of(unit("x", "d1", 1, 0)));

    // when
    List<List<ScoredRef>> results =
        index.search(A, List.of(new double[] {0, 0}), TOP_K, SearchFilter.NONE);

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
      List<String> got = ids(search(A, diagonal(), TOP_K, SearchFilter.NONE));

      // then
      assertThat(got).containsExactly("z-first", "a-second");
    }

    @Test
    @DisplayName("길이가 다른 벡터와 영벡터 행은 검색되지 않는다")
    void unusableVectorsAreExcluded() {
      // when
      List<String> got = ids(search(A, diagonal(), TOP_K, SearchFilter.NONE));

      // then: 쓸 수 있는 벡터는 검색된다(대조군)
      assertThat(got).contains("z-first", "a-second").doesNotContain("three", "zero");
    }

    @Test
    @DisplayName("차원이 다르거나 영벡터인 행도 저장은 된다")
    void unusableVectorsAreStored() {
      // when
      Map<String, double[]> got = index.vectorsOf(A, List.of("three", "zero"));

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
      List<ScoredRef> hits = search(A, east(), 1, SearchFilter.NONE);

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
      List<ScoredRef> hits = search(A, east(), 1, new SearchFilter(true, List.of("/rules")));

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
    assertThat(ids(search(A, east(), TOP_K, SearchFilter.NONE))).containsExactly("y", "x");
  }

  @Test
  @DisplayName("한 배치에 같은 unitId가 다시 나와도 동점 순서는 처음 자리다")
  void repeatedUnitInBatchKeepsFirstLoadOrder() {
    // given: 한 배치에서 x(서쪽) → y(동쪽) → x(동쪽, y와 같은 벡터) 순서로 넣는다
    index.upsert(A, List.of(unit("x", "d1", -1, 0), unit("y", "d2", 1, 0), unit("x", "d1", 1, 0)));

    // when
    List<ScoredRef> hits = search(A, east(), TOP_K, SearchFilter.NONE);

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
    assertThat(ids(search(A, east(), TOP_K, SearchFilter.NONE))).containsExactly("x");
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
    assertThat(ids(search(B, east(), TOP_K, SearchFilter.NONE))).containsExactly("x");
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
      Map<String, double[]> got = index.vectorsOf(A, List.of("y", "nope", "x", "z"));

      // then
      assertThat(got.keySet()).containsExactlyInAnyOrder("x", "y");
      assertThat(got.get("x")).containsExactly(0.5, 0.25);
    }

    @Test
    @DisplayName("B 공간의 벡터는 B로 읽는다")
    void vectorsOfOtherSpace() {
      // when
      Map<String, double[]> got = index.vectorsOf(B, List.of("z"));

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
