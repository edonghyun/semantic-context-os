package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.spi.store.UnitStore.DocumentInfo;
import io.scos.spi.store.UnitStore.Range;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 적재·유지보수·운영 조회 포트의 공통 계약. 읽기 계약과 같은 저장 상태를 관측한다.
 *
 * <p>매 테스트의 준비에서 newMaintenance()를 먼저 호출해 새 저장 상태를 만든 뒤 unitStore()·vectorIndex()를 호출한다. 두 조회 훅은 먼저
 * 만든 유지보수 포트와 같은 상태를 공유하는 어댑터를 반환해야 한다.
 */
@DisplayName("유지보수는 공간별 적재 순서·소유·삭제·저장 벡터를 보존한다")
public abstract class UnitMaintenanceContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  static final Range ALL = new Range(0, 10);
  static final String LATEST_IN_D1 = "2026-10-03T00:00:00.000Z";
  static final String CAPTURED_IN_A = "2026-10-01T00:00:00.000Z";

  protected UnitMaintenance maintenance;
  protected UnitStore store;
  protected VectorIndex index;

  protected abstract UnitMaintenance newMaintenance();

  protected abstract UnitStore unitStore();

  protected abstract VectorIndex vectorIndex();

  @BeforeEach
  void setUpContract() {
    maintenance = newMaintenance();
    store = unitStore();
    index = vectorIndex();
  }

  @Test
  @DisplayName("빈 저장소에는 압축 유닛이 없다")
  void emptyStoreHasNoCompactedUnits() {
    long count = maintenance.countCompacted();

    // then
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("압축 유닛은 모든 공간에서 합산하고 다른 티어는 세지 않는다")
  void countsCompactedAcrossAllSpaces() {
    maintenance.upsert(
        A,
        List.of(
            ContextUnits.unit("raw").build(),
            ContextUnits.unit("old").archived().build(),
            ContextUnits.unit("a").compacted().build()));
    maintenance.upsert(B, List.of(ContextUnits.unit("b").compacted().build()));

    long count = maintenance.countCompacted();

    // then
    assertThat(count).isEqualTo(2L);
  }

  private static ContextUnit unit(String id, String doc) {
    return ContextUnits.unit(id).inDocument(doc).build();
  }

  @Test
  @DisplayName("다른 출처가 새 문서와 소유 문서를 한 배치로 쓰면 아무 유닛도 바꾸지 않는다")
  void foreignSourceBatchIsRejectedBeforeAnyWrite() {
    maintenance.upsert(A, List.of(unit("owner", "doc-1", "source-a")));

    assertThatThrownBy(
            () ->
                maintenance.upsert(
                    A,
                    List.of(unit("new", "doc-2", "source-b"), unit("owner", "doc-1", "source-b"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doc-1");

    // then
    assertThat(maintenance.previousUnits(A, List.of("owner", "new")))
        .extracting(ContextUnit::id, u -> u.provenance().sourceId())
        .containsExactly(tuple("owner", "source-a"));
  }

  @Test
  @DisplayName("새 문서 하나에 출처가 섞인 배치도 전부 거부한다")
  void mixedSourcesForNewDocumentAreRejected() {
    assertThatThrownBy(
            () ->
                maintenance.upsert(
                    A,
                    List.of(
                        unit("first", "doc-1", "source-a"), unit("second", "doc-1", "source-b"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doc-1");

    // then
    assertThat(maintenance.listAll(A)).isEmpty();
  }

  private static ContextUnit unit(String id, String doc, String sourceId) {
    return ContextUnits.unit(id).inDocument(doc).source(sourceId).build();
  }

  private static IndexedUnit unit(String id, String doc, double... v) {
    return new IndexedUnit(id, doc, "/knowledge", false, v);
  }

  static List<String> ids(List<ContextUnit> units) {
    return units.stream().map(ContextUnit::id).toList();
  }

  List<String> idsOf(SpaceId space, String document) {
    return ids(store.listByDocument(space, AccessScope.singleUser(), document, ALL));
  }

  @Test
  @DisplayName("저장된 관측 시각이 모두 빈 문자열이면 최신 시각도 없다")
  void emptyCapturedAtDoesNotInventLatestTimestamp() {
    maintenance.upsert(
        A,
        List.of(
            ContextUnits.unit("without-time").inDocument("without-time").capturedAt("").build()));
    // then
    assertThat(maintenance.latestCapturedAt(A)).isEmpty();
  }

  @Nested
  @DisplayName("A에 u1·u2, B에 u1이 적재되어 있을 때")
  class PreviousBatch {
    @BeforeEach
    void givenPreviousBatch() {
      maintenance.upsert(A, List.of(unit("u1", "d1"), unit("u2", "d2")));
      maintenance.upsert(
          B, List.of(ContextUnits.unit("u1").inDocument("other").titled("B").build()));
    }

    @Test
    @DisplayName("이전 유닛은 없는 id를 생략하고 중복을 포함해 요청 순서로 반환한다")
    void previousUnitsInRequestOrder() {
      // when
      var previous = maintenance.previousUnits(A, List.of("u2", "missing", "u1", "u2"));

      // then
      assertThat(ids(previous)).containsExactly("u2", "u1", "u2");
    }

    @Test
    @DisplayName("이전 유닛은 다른 공간의 같은 id와 섞이지 않는다")
    void previousUnitsInOwnSpace() {
      // when
      var previous = maintenance.previousUnits(B, List.of("u1", "u2"));

      // then
      assertThat(previous).extracting(ContextUnit::title).containsExactly("B");
    }
  }

  @Nested
  @DisplayName("u1·u2를 넣은 뒤 u1을 새 제목·링크로 다시 넣으면")
  class U1Replaced {
    @BeforeEach
    void givenU1Replaced() {
      maintenance.upsert(A, List.of(unit("u1", "d1"), unit("u2", "d1")));
      maintenance.upsert(
          A,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("d1")
                  .titled("새 제목")
                  .capturedAt("2026-10-05T00:00:00.000Z")
                  .linksTo("d9")
                  .build()));
    }

    @Test
    @DisplayName("적재 순서는 처음 자리다")
    void upsertKeepsSeq() {
      // when
      List<String> got = idsOf(A, "d1");

      // then
      assertThat(got).containsExactly("u1", "u2");
    }

    @Test
    @DisplayName("내용이 바뀐다")
    void upsertReplacesContent() {
      // when
      ContextUnit u1 = store.listByDocument(A, AccessScope.singleUser(), "d1", ALL).get(0);

      // then
      assertThat(u1.title()).isEqualTo("새 제목");
      assertThat(u1.links()).containsExactly("d9");
    }
  }

  @Nested
  @DisplayName("한 배치에서 u1 → u2 → u1(새 제목) 순서로 넣으면")
  class U1RepeatedInOneBatch {
    @BeforeEach
    void givenU1RepeatedInOneBatch() {
      maintenance.upsert(
          A,
          List.of(
              unit("u1", "d1"),
              unit("u2", "d1"),
              ContextUnits.unit("u1").inDocument("d1").titled("새 제목").build()));
    }

    @Test
    @DisplayName("마지막 내용이 남는다")
    void repeatedUnitInBatchKeepsLastContent() {
      // when
      List<ContextUnit> got = maintenance.previousUnits(A, List.of("u1"));

      // then
      assertThat(got).extracting(ContextUnit::title).containsExactly("새 제목");
    }

    @Test
    @DisplayName("적재 순서는 처음 자리다")
    void repeatedUnitInBatchKeepsFirstLoadOrder() {
      // when
      List<String> got = idsOf(A, "d1");

      // then
      assertThat(got).containsExactly("u1", "u2");
    }
  }

  @Nested
  @DisplayName("d1에 u1·u2, d2에 u3을 넣은 뒤 u1을 d2로 다시 넣으면")
  class U1MovedToOtherDocument {
    /** TS store-sqlite도 ON CONFLICT(id)로 document_id만 바꾸고 rowid(적재 순서)를 유지한다. */
    @BeforeEach
    void givenU1MovedToOtherDocument() {
      maintenance.upsert(A, List.of(unit("u1", "d1", "src-1"), unit("u2", "d1", "src-1")));
      maintenance.upsert(A, List.of(unit("u3", "d2", "src-1")));
      maintenance.upsert(A, List.of(unit("u1", "d2", "src-1")));
    }

    @Test
    @DisplayName("옛 문서 목록에서 빠진다")
    void movedUnitLeavesOldDocument() {
      // when
      List<String> got = idsOf(A, "d1");

      // then
      assertThat(got).containsExactly("u2");
    }

    @Test
    @DisplayName("새 문서에는 처음 적재 자리로 들어간다")
    void movedUnitKeepsLoadOrderInNewDocument() {
      // when
      List<String> got = idsOf(A, "d2");

      // then
      assertThat(got).containsExactly("u1", "u3");
    }

    @Test
    @DisplayName("문서 정보의 unitCount가 옛 문서에서 줄고 새 문서에서 는다")
    void movedUnitMovesUnitCount() {
      // when
      List<DocumentInfo> infos =
          store.getDocuments(A, AccessScope.singleUser(), List.of("d1", "d2"));

      // then
      assertThat(infos).extracting(DocumentInfo::unitCount).containsExactly(1, 2);
    }

    @Test
    @DisplayName("옛 문서의 마지막 유닛까지 옮기면 그 문서는 출처의 문서 목록·문서 정보에서 빠진다")
    void lastMovedUnitEmptiesOldDocument() {
      // when
      maintenance.upsert(A, List.of(unit("u2", "d2", "src-1")));

      // then
      assertThat(maintenance.listDocumentIds(A, "src-1")).containsExactly("d2");
      assertThat(store.getDocuments(A, AccessScope.singleUser(), List.of("d1", "d2")))
          .extracting(DocumentInfo::documentId, DocumentInfo::unitCount)
          .containsExactly(tuple("d2", 3));
    }
  }

  @Nested
  @DisplayName("A2: 공간 A가 shared-doc을 먼저 쓴 상태에서")
  class SharedDocumentOwnedByA {
    @BeforeEach
    void givenSharedDocumentOwnedByA() {
      maintenance.upsert(A, List.of(unit("a-1", "shared-doc"), unit("a-2", "own-doc")));
    }

    @Test
    @DisplayName("B가 fresh-doc·shared-doc을 한 배치로 쓰면 그 문서를 밝혀 거부된다")
    void otherSpaceWriteIsRejected() {
      // when·then
      assertThatThrownBy(
              () ->
                  maintenance.upsert(
                      B, List.of(unit("b-1", "fresh-doc"), unit("b-2", "shared-doc"))))
          .isInstanceOf(SpaceOwnershipException.class)
          .hasMessageContaining("shared-doc");
    }

    @Test
    @DisplayName("소유 공간의 재기록은 허용된다")
    void ownerMayRewrite() {
      // when
      maintenance.upsert(A, List.of(unit("a-3", "shared-doc")));

      // then
      assertThat(idsOf(A, "shared-doc")).containsExactly("a-1", "a-3");
    }

    @Nested
    @DisplayName("B의 fresh-doc·shared-doc 배치가 거부된 뒤")
    class AfterRejectedBatch {
      @BeforeEach
      void givenBatchRejectedForSharedDocument() {
        try {
          maintenance.upsert(B, List.of(unit("b-1", "fresh-doc"), unit("b-2", "shared-doc")));
        } catch (SpaceOwnershipException rejected) {
          return;
        }
        throw new IllegalStateException("given 준비 실패: B의 배치가 거부돼야 한다");
      }

      @Test
      @DisplayName("거부된 배치의 유닛은 하나도 남지 않는다")
      void rejectedBatchLeavesNothing() {
        // when·then
        assertThat(idsOf(B, "shared-doc")).isEmpty();
        assertThat(idsOf(B, "fresh-doc")).isEmpty();
      }

      @Test
      @DisplayName("소유 공간의 유닛은 그대로다")
      void rejectedBatchKeepsOwner() {
        // when
        List<String> got = idsOf(A, "shared-doc");

        // then
        assertThat(got).containsExactly("a-1");
      }
    }
  }

  @Nested
  @DisplayName("src-1에 doc-1(u1·u2·u3)·doc-2(u4), src-2에 doc-3(u5)이 있을 때")
  class TwoSources {
    @BeforeEach
    void givenTwoSources() {
      maintenance.upsert(
          A,
          List.of(
              unit("u1", "doc-1", "src-1"),
              unit("u2", "doc-1", "src-1"),
              unit("u3", "doc-1", "src-1"),
              unit("u4", "doc-2", "src-1"),
              unit("u5", "doc-3", "src-2")));
    }

    @Test
    @DisplayName("listDocumentIds는 출처별 문서 목록이다")
    void listDocumentIdsPerSource() {
      // when·then
      assertThat(maintenance.listDocumentIds(A, "src-1")).containsExactly("doc-1", "doc-2");
      assertThat(maintenance.listDocumentIds(A, "src-2")).containsExactly("doc-3");
    }

    @Test
    @DisplayName("deleteByDocument는 keepIds 밖 유닛만 지운다")
    void deletesOutsideKeepIds() {
      // when
      maintenance.deleteByDocument(A, "src-1", "doc-1", List.of("u2"));

      // then
      assertThat(ids(maintenance.previousUnits(A, List.of("u1", "u2", "u3", "u4"))))
          .containsExactly("u2", "u4");
    }

    @Test
    @DisplayName("deleteByDocument는 지운 id를 적재 순서로 돌려준다")
    void returnsDeletedIdsInLoadOrder() {
      // when
      List<String> deleted = maintenance.deleteByDocument(A, "src-1", "doc-1", List.of("u2"));

      // then
      assertThat(deleted).containsExactly("u1", "u3");
    }

    @Test
    @DisplayName("deleteByDocument는 출처가 다르면 지운 id가 없다")
    void deleteIsScopedBySource() {
      // when
      List<String> deleted = maintenance.deleteByDocument(A, "src-2", "doc-2", List.of());

      // then
      assertThat(deleted).isEmpty();
    }

    @Test
    @DisplayName("deleteByDocument는 공간이 다르면 지운 id가 없다")
    void deleteIsScopedBySpace() {
      // when
      List<String> deleted =
          maintenance.deleteByDocument(new SpaceId("other"), "src-1", "doc-2", List.of());

      // then
      assertThat(deleted).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutOfScope.class)
    @DisplayName("출처나 공간이 다른 deleteByDocument 뒤에도 유닛은 그대로다")
    void deleteOutOfScopeKeepsUnits(OutOfScope scope) {
      // when
      maintenance.deleteByDocument(new SpaceId(scope.space), scope.source, "doc-2", List.of());

      // then
      assertThat(ids(maintenance.previousUnits(A, List.of("u4")))).containsExactly("u4");
    }

    @Nested
    @DisplayName("doc-2의 유일한 유닛 u4를 지운 뒤")
    class DocumentEmptied {
      @BeforeEach
      void givenDocumentEmptied() {
        List<String> deleted = maintenance.deleteByDocument(A, "src-1", "doc-2", List.of());
        if (!deleted.equals(List.of("u4")))
          throw new IllegalStateException("given 준비 실패: doc-2의 유닛 u4를 지워야 한다 — " + deleted);
      }

      @Test
      @DisplayName("출처의 문서 목록에서도 빠진다")
      void emptiedDocumentLeavesList() {
        // when
        List<String> documents = maintenance.listDocumentIds(A, "src-1");

        // then
        assertThat(documents).containsExactly("doc-1");
      }

      @Test
      @DisplayName("다른 문서의 유닛은 남는다")
      void emptiedDocumentKeepsOtherUnits() {
        // when
        List<ContextUnit> all = maintenance.listAll(A);

        // then
        assertThat(ids(all)).containsExactly("u1", "u2", "u3", "u5");
      }
    }
  }

  @Nested
  @DisplayName("d1에 b·a·c 순서로, d2에 x를 넣은 상태에서")
  class DocumentInLoadOrder {
    @BeforeEach
    void givenDocumentInLoadOrder() {
      maintenance.upsert(
          A, List.of(unit("b", "d1"), unit("a", "d1"), unit("c", "d1"), unit("x", "d2")));
    }

    @Test
    @DisplayName("빈 배치 upsert는 저장된 유닛을 바꾸지 않는다")
    void emptyBatchChangesNothing() {
      // when
      maintenance.upsert(A, List.of());

      // then
      assertThat(idsOf(A, "d1")).containsExactly("b", "a", "c");
    }
  }

  @Nested
  @DisplayName("d1(첫 유닛 \"첫 제목\", 둘째 2026-10-03)과 d2(2026-09-01)가 있을 때")
  class TwoDocuments {
    @BeforeEach
    void givenTwoDocuments() {
      maintenance.upsert(
          A,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("d1")
                  .titled("첫 제목")
                  .uri("fixture://d1")
                  .source("fixture")
                  .build(),
              ContextUnits.unit("u2")
                  .inDocument("d1")
                  .titled("둘째")
                  .capturedAt(LATEST_IN_D1)
                  .build(),
              ContextUnits.unit("u3")
                  .inDocument("d2")
                  .titled("다른")
                  .capturedAt("2026-09-01T00:00:00.000Z")
                  .build()));
    }

    @Test
    @DisplayName("공간의 latestCapturedAt은 모든 문서의 최댓값이다")
    void spaceLatestCapturedAt() {
      // when
      Optional<String> latest = maintenance.latestCapturedAt(A);

      // then
      assertThat(latest).contains(LATEST_IN_D1);
    }

    @Test
    @DisplayName("빈 공간의 latestCapturedAt은 비어 있다")
    void emptySpaceHasNoLatestCapturedAt() {
      // when
      Optional<String> latest = maintenance.latestCapturedAt(B);

      // then
      assertThat(latest).isEmpty();
    }
  }

  @Nested
  @DisplayName("문자열 순서와 시각 순서가 다른 capturedAt 셋이 있을 때")
  class CapturedAtThatSortDifferentlyAsInstants {
    /** d1에 두 개, d2에 하나. 비교는 시각 의미가 아니다(TS·SQLite BINARY). */
    @BeforeEach
    void givenCapturedAtThatSortDifferentlyAsInstants() {
      maintenance.upsert(
          A,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("d1")
                  .titled("a")
                  .capturedAt("2026-10-03T00:00:00.500Z")
                  .build(),
              ContextUnits.unit("u2")
                  .inDocument("d1")
                  .titled("b")
                  .capturedAt("2026-10-03T00:00:00Z")
                  .build(),
              ContextUnits.unit("u3")
                  .inDocument("d2")
                  .titled("c")
                  .capturedAt("2026-10-03T09:00:00+09:00")
                  .build()));
    }

    @Test
    @DisplayName("공간의 latestCapturedAt도 코드 유닛 문자열의 최댓값이다")
    void spaceLatestCapturedAtIsCodeUnitMax() {
      // when
      Optional<String> latest = maintenance.latestCapturedAt(A);

      // then: 공간 전체에서는 "T09…"가 "T00…"보다 크다(시각으로는 더 이르다)
      assertThat(latest).contains("2026-10-03T09:00:00+09:00");
    }
  }

  @Nested
  @DisplayName("같은 유닛 id u1이 A·B에 있고 B의 u1이 A의 d2도 가리킬 때(A2)")
  class SameUnitIdInTwoSpaces {
    /** 문서 id는 공간이 소유한다(A2). */
    @BeforeEach
    void givenSameUnitIdInTwoSpaces() {
      maintenance.upsert(
          A,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("d1")
                  .titled("A")
                  .capturedAt(CAPTURED_IN_A)
                  .linksTo("d2")
                  .build(),
              ContextUnits.unit("u2").inDocument("d2").capturedAt(CAPTURED_IN_A).build()));
      maintenance.upsert(
          B,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("b-d1")
                  .titled("B")
                  .capturedAt("2026-12-01T00:00:00.000Z")
                  .linksTo("b-d2", "d2")
                  .build(),
              unit("u9", "b-d2")));
    }

    @Test
    @DisplayName("공간의 latestCapturedAt은 자기 공간 것만 본다")
    void latestCapturedAtIsPerSpace() {
      // when
      Optional<String> latest = maintenance.latestCapturedAt(A);

      // then
      assertThat(latest).contains("2026-10-01T00:00:00.000Z");
    }
  }

  /** doc-2를 지우되 출처나 공간 하나가 다른 범위. */
  enum OutOfScope {
    OTHER_SOURCE("출처", A.value(), "src-2"),
    OTHER_SPACE("공간", "other", "src-1");

    final String label;
    final String space;
    final String source;

    OutOfScope(String label, String space, String source) {
      this.label = label;
      this.space = space;
      this.source = source;
    }

    @Override
    public String toString() {
      return label + "이 다른 삭제";
    }
  }

  @Nested
  @DisplayName("3차원 벡터와 영벡터가 있을 때")
  class UnusableVectors {
    @BeforeEach
    void givenUnusableVectors() {
      index.upsert(A, List.of(unit("three", "d3", 1, 1, 1), unit("zero", "d4", 0, 0)));
    }

    @Test
    @DisplayName("차원이 다르거나 영벡터인 행도 저장은 된다")
    void unusableVectorsAreStored() {
      // when
      Map<String, double[]> got = maintenance.storedVectors(A, List.of("three", "zero"));

      // then
      assertThat(got.keySet()).containsExactlyInAnyOrder("three", "zero");
    }
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
    @DisplayName("storedVectors는 그 공간에 저장된 벡터만 id별로 돌려준다")
    void storedVectors() {
      // when
      Map<String, double[]> got = maintenance.storedVectors(A, List.of("y", "nope", "x", "z"));

      // then
      assertThat(got.keySet()).containsExactlyInAnyOrder("x", "y");
      assertThat(got.get("x")).containsExactly(0.5, 0.25);
    }

    @Test
    @DisplayName("storedVectors는 B 공간의 벡터를 B로 읽는다")
    void storedVectorsOtherSpace() {
      // when
      Map<String, double[]> got = maintenance.storedVectors(B, List.of("z"));

      // then
      assertThat(got.get("z")).containsExactly(1.0, 1.0);
    }
  }
}
