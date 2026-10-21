package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.spi.store.UnitStore.DocumentInfo;
import io.scos.spi.store.UnitStore.LinkedUnit;
import io.scos.spi.store.UnitStore.Range;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 유닛 저장소 포트 계약(CONTRACTS A1·D·E, TS search-contract-testkit units/documents/links 블록의 Java 판). */
@DisplayName("유닛 저장소는 공간별로 유닛·문서·링크를 적재 순서로 둔다(CONTRACTS A1·D·E)")
public abstract class UnitStoreContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  static final Range ALL = new Range(0, 10);
  static final String LATEST_IN_D1 = "2026-10-03T00:00:00.000Z";
  static final String CAPTURED_IN_A = "2026-10-01T00:00:00.000Z";

  protected UnitStore store;

  protected abstract UnitStore newStore();

  @BeforeEach
  void setUpContract() {
    store = newStore();
  }

  /** 기본 유닛(제목 "제목 <id>", 적재 시각 기본값). 차이가 있는 유닛은 {@link ContextUnits#unit}로 직접 만든다. */
  protected static ContextUnit unit(String id, String doc) {
    return ContextUnits.unit(id).inDocument(doc).build();
  }

  private static ContextUnit unit(String id, String doc, String sourceId) {
    return ContextUnits.unit(id).inDocument(doc).source(sourceId).build();
  }

  static List<String> ids(List<ContextUnit> units) {
    return units.stream().map(ContextUnit::id).toList();
  }

  List<String> idsOf(SpaceId space, String document) {
    return ids(store.listByDocument(space, document, ALL));
  }

  @Test
  @DisplayName("getMany는 있는 유닛을 입력 순서대로 나온 자리마다 돌려준다")
  void getManyOrderMissingDuplicates() {
    // given
    store.upsert(
        A,
        List.of(
            unit("u1", "d1"),
            unit("u2", "d1"),
            ContextUnits.unit("u3").inDocument("d2").titled("제목 u3").build()));

    // when
    List<ContextUnit> got = store.getMany(A, List.of("u3", "missing", "u1", "u3"));

    // then
    assertThat(ids(got)).containsExactly("u3", "u1", "u3");
    assertThat(got.get(0).title()).isEqualTo("제목 u3");
  }

  @Nested
  @DisplayName("d1에 b·a·c 순서로, d2에 x를 넣은 상태에서")
  class DocumentInLoadOrder {
    @BeforeEach
    void givenDocumentInLoadOrder() {
      store.upsert(A, List.of(unit("b", "d1"), unit("a", "d1"), unit("c", "d1"), unit("x", "d2")));
    }

    @Test
    @DisplayName("listByDocument는 적재 순서대로 range 구간을 돌려준다")
    void listByDocumentRange() {
      // when·then
      assertThat(ids(store.listByDocument(A, "d1", new Range(0, 2)))).containsExactly("b", "a");
      assertThat(ids(store.listByDocument(A, "d1", new Range(1, 10)))).containsExactly("a", "c");
    }

    @Test
    @DisplayName("문서 끝을 넘는 offset은 빈 목록이다")
    void listByDocumentPastEnd() {
      // when
      List<ContextUnit> got = store.listByDocument(A, "d1", new Range(5, 10));

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("없는 문서는 빈 목록이다")
    void listByUnknownDocument() {
      // when
      List<ContextUnit> got = store.listByDocument(A, "nope", ALL);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("빈 배치 upsert는 저장된 유닛을 바꾸지 않는다")
    void emptyBatchChangesNothing() {
      // when
      store.upsert(A, List.of());

      // then
      assertThat(idsOf(A, "d1")).containsExactly("b", "a", "c");
    }
  }

  @Nested
  @DisplayName("u1·u2를 넣은 뒤 u1을 새 제목·링크로 다시 넣으면")
  class U1Replaced {
    @BeforeEach
    void givenU1Replaced() {
      store.upsert(A, List.of(unit("u1", "d1"), unit("u2", "d1")));
      store.upsert(
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
      ContextUnit u1 = store.listByDocument(A, "d1", ALL).get(0);

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
      store.upsert(
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
      List<ContextUnit> got = store.getMany(A, List.of("u1"));

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
      store.upsert(A, List.of(unit("u1", "d1", "src-1"), unit("u2", "d1", "src-1")));
      store.upsert(A, List.of(unit("u3", "d2", "src-1")));
      store.upsert(A, List.of(unit("u1", "d2", "src-1")));
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
      List<DocumentInfo> infos = store.getDocuments(A, List.of("d1", "d2"));

      // then
      assertThat(infos).extracting(DocumentInfo::unitCount).containsExactly(1, 2);
    }

    @Test
    @DisplayName("옛 문서의 마지막 유닛까지 옮기면 그 문서는 출처의 문서 목록·문서 정보에서 빠진다")
    void lastMovedUnitEmptiesOldDocument() {
      // when
      store.upsert(A, List.of(unit("u2", "d2", "src-1")));

      // then
      assertThat(store.listDocumentIds(A, "src-1")).containsExactly("d2");
      assertThat(store.getDocuments(A, List.of("d1", "d2")))
          .extracting(DocumentInfo::documentId, DocumentInfo::unitCount)
          .containsExactly(tuple("d2", 3));
    }
  }

  @Test
  @DisplayName("linkedUnits는 입력 밖 링크 대상 유닛을 적재 순서로 한 번씩 돌려준다")
  void linkedUnits() {
    // given
    store.upsert(
        A,
        List.of(
            ContextUnits.unit("t2").inDocument("target").titled("t2").build(),
            ContextUnits.unit("hub1")
                .inDocument("hub")
                .titled("hub1")
                .linksTo("target", "other")
                .build(),
            ContextUnits.unit("t1").inDocument("target").titled("t1").linksTo("hub").build(),
            ContextUnits.unit("o1").inDocument("other").titled("o1").build(),
            ContextUnits.unit("hub2").inDocument("hub").titled("hub2").linksTo("target").build()));

    // when
    List<LinkedUnit> got = store.linkedUnits(A, List.of("hub1", "hub2", "t1"));

    // then: t1·hub1·hub2는 입력이라 제외(t1 → hub 링크의 대상이어도), 대상 유닛 적재 순서 t2 < o1
    assertThat(got).containsExactly(new LinkedUnit("t2", "target"), new LinkedUnit("o1", "other"));
  }

  @Test
  @DisplayName("링크가 없는 유닛의 linkedUnits는 비어 있다")
  void noLinks() {
    // given
    store.upsert(A, List.of(ContextUnits.unit("n1").inDocument("none").titled("n1").build()));

    // when
    List<LinkedUnit> got = store.linkedUnits(A, List.of("n1"));

    // then
    assertThat(got).isEmpty();
  }

  @Nested
  @DisplayName("d1(첫 유닛 \"첫 제목\", 둘째 2026-10-03)과 d2(2026-09-01)가 있을 때")
  class TwoDocuments {
    @BeforeEach
    void givenTwoDocuments() {
      store.upsert(
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
    @DisplayName("문서 정보는 있는 문서만 요청 순서로 돌려준다")
    void documentsInRequestOrder() {
      // when
      List<DocumentInfo> infos = store.getDocuments(A, List.of("d2", "missing", "d1"));

      // then
      assertThat(infos).extracting(DocumentInfo::documentId).containsExactly("d2", "d1");
    }

    @Test
    @DisplayName("문서 정보는 첫 유닛의 제목·uri·출처와 유닛 수·capturedAt 최댓값이다")
    void documentInfoFields() {
      // when
      DocumentInfo d1 = store.getDocuments(A, List.of("d1")).get(0);

      // then
      assertThat(d1)
          .extracting(
              DocumentInfo::title,
              DocumentInfo::uri,
              DocumentInfo::sourceId,
              DocumentInfo::unitCount,
              DocumentInfo::latestCapturedAt)
          .containsExactly("첫 제목", "fixture://d1", "fixture", 2, Optional.of(LATEST_IN_D1));
    }

    @Test
    @DisplayName("공간의 latestCapturedAt은 모든 문서의 최댓값이다")
    void spaceLatestCapturedAt() {
      // when
      Optional<String> latest = store.latestCapturedAt(A);

      // then
      assertThat(latest).contains(LATEST_IN_D1);
    }

    @Test
    @DisplayName("빈 공간의 latestCapturedAt은 비어 있다")
    void emptySpaceHasNoLatestCapturedAt() {
      // when
      Optional<String> latest = store.latestCapturedAt(B);

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
      store.upsert(
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
    @DisplayName("문서의 latestCapturedAt은 코드 유닛 문자열의 최댓값이다")
    void latestCapturedAtIsCodeUnitMax() {
      // when
      Optional<String> latest = store.getDocuments(A, List.of("d1")).get(0).latestCapturedAt();

      // then: d1 안에서는 '.'(0x2E) < 'Z'(0x5A)라 "…00Z"가 최댓값이다
      assertThat(latest).contains("2026-10-03T00:00:00Z");
    }

    @Test
    @DisplayName("공간의 latestCapturedAt도 코드 유닛 문자열의 최댓값이다")
    void spaceLatestCapturedAtIsCodeUnitMax() {
      // when
      Optional<String> latest = store.latestCapturedAt(A);

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
      store.upsert(
          A,
          List.of(
              ContextUnits.unit("u1")
                  .inDocument("d1")
                  .titled("A")
                  .capturedAt(CAPTURED_IN_A)
                  .linksTo("d2")
                  .build(),
              ContextUnits.unit("u2").inDocument("d2").capturedAt(CAPTURED_IN_A).build()));
      store.upsert(
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
    @DisplayName("같은 유닛 id라도 공간마다 따로 조회된다")
    void getManyIsPerSpace() {
      // when
      List<ContextUnit> got = store.getMany(B, List.of("u1", "u2"));

      // then
      assertThat(got).extracting(ContextUnit::title).containsExactly("B");
    }

    @Test
    @DisplayName("다른 공간의 문서는 listByDocument로 보이지 않는다")
    void documentsArePerSpace() {
      // given
      if (!idsOf(A, "d2").equals(List.of("u2")))
        throw new IllegalStateException("given 준비 실패: A에서는 d2의 u2가 보여야 한다");

      // when
      List<String> got = idsOf(B, "d2");

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("다른 공간을 가리키는 링크는 그 공간의 유닛을 드러내지 않는다")
    void linksArePerSpace() {
      // when·then
      assertThat(store.linkedUnits(A, List.of("u1"))).containsExactly(new LinkedUnit("u2", "d2"));
      assertThat(store.linkedUnits(B, List.of("u1"))).containsExactly(new LinkedUnit("u9", "b-d2"));
    }

    @Test
    @DisplayName("문서 정보는 자기 공간 것만이다")
    void documentInfoIsPerSpace() {
      // when
      List<DocumentInfo> infos = store.getDocuments(A, List.of("d1", "b-d1"));

      // then
      assertThat(infos).extracting(DocumentInfo::title).containsExactly("A");
    }

    @Test
    @DisplayName("공간의 latestCapturedAt은 자기 공간 것만 본다")
    void latestCapturedAtIsPerSpace() {
      // when
      Optional<String> latest = store.latestCapturedAt(A);

      // then
      assertThat(latest).contains("2026-10-01T00:00:00.000Z");
    }
  }

  @Nested
  @DisplayName("A2: 공간 A가 shared-doc을 먼저 쓴 상태에서")
  class SharedDocumentOwnedByA {
    @BeforeEach
    void givenSharedDocumentOwnedByA() {
      store.upsert(A, List.of(unit("a-1", "shared-doc"), unit("a-2", "own-doc")));
    }

    @Test
    @DisplayName("B가 fresh-doc·shared-doc을 한 배치로 쓰면 그 문서를 밝혀 거부된다")
    void otherSpaceWriteIsRejected() {
      // when·then
      assertThatThrownBy(
              () -> store.upsert(B, List.of(unit("b-1", "fresh-doc"), unit("b-2", "shared-doc"))))
          .isInstanceOf(SpaceOwnershipException.class)
          .hasMessageContaining("shared-doc");
    }

    @Test
    @DisplayName("소유 공간의 재기록은 허용된다")
    void ownerMayRewrite() {
      // when
      store.upsert(A, List.of(unit("a-3", "shared-doc")));

      // then
      assertThat(idsOf(A, "shared-doc")).containsExactly("a-1", "a-3");
    }

    @Nested
    @DisplayName("B의 fresh-doc·shared-doc 배치가 거부된 뒤")
    class AfterRejectedBatch {
      @BeforeEach
      void givenBatchRejectedForSharedDocument() {
        try {
          store.upsert(B, List.of(unit("b-1", "fresh-doc"), unit("b-2", "shared-doc")));
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
  @DisplayName("src-1에 doc-1(u1·u2·u3)·doc-2(u4), src-2에 doc-3(u5)이 있을 때")
  class TwoSources {
    @BeforeEach
    void givenTwoSources() {
      store.upsert(
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
      assertThat(store.listDocumentIds(A, "src-1")).containsExactly("doc-1", "doc-2");
      assertThat(store.listDocumentIds(A, "src-2")).containsExactly("doc-3");
    }

    @Test
    @DisplayName("deleteByDocument는 keepIds 밖 유닛만 지운다")
    void deletesOutsideKeepIds() {
      // when
      store.deleteByDocument(A, "src-1", "doc-1", List.of("u2"));

      // then
      assertThat(ids(store.getMany(A, List.of("u1", "u2", "u3", "u4"))))
          .containsExactly("u2", "u4");
    }

    @Test
    @DisplayName("deleteByDocument는 지운 id를 적재 순서로 돌려준다")
    void returnsDeletedIdsInLoadOrder() {
      // when
      List<String> deleted = store.deleteByDocument(A, "src-1", "doc-1", List.of("u2"));

      // then
      assertThat(deleted).containsExactly("u1", "u3");
    }

    @Test
    @DisplayName("deleteByDocument는 출처가 다르면 지운 id가 없다")
    void deleteIsScopedBySource() {
      // when
      List<String> deleted = store.deleteByDocument(A, "src-2", "doc-2", List.of());

      // then
      assertThat(deleted).isEmpty();
    }

    @Test
    @DisplayName("deleteByDocument는 공간이 다르면 지운 id가 없다")
    void deleteIsScopedBySpace() {
      // when
      List<String> deleted =
          store.deleteByDocument(new SpaceId("other"), "src-1", "doc-2", List.of());

      // then
      assertThat(deleted).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutOfScope.class)
    @DisplayName("출처나 공간이 다른 deleteByDocument 뒤에도 유닛은 그대로다")
    void deleteOutOfScopeKeepsUnits(OutOfScope scope) {
      // when
      store.deleteByDocument(new SpaceId(scope.space), scope.source, "doc-2", List.of());

      // then
      assertThat(ids(store.getMany(A, List.of("u4")))).containsExactly("u4");
    }

    @Nested
    @DisplayName("doc-2의 유일한 유닛 u4를 지운 뒤")
    class DocumentEmptied {
      @BeforeEach
      void givenDocumentEmptied() {
        List<String> deleted = store.deleteByDocument(A, "src-1", "doc-2", List.of());
        if (!deleted.equals(List.of("u4")))
          throw new IllegalStateException("given 준비 실패: doc-2의 유닛 u4를 지워야 한다 — " + deleted);
      }

      @Test
      @DisplayName("출처의 문서 목록에서도 빠진다")
      void emptiedDocumentLeavesList() {
        // when
        List<String> documents = store.listDocumentIds(A, "src-1");

        // then
        assertThat(documents).containsExactly("doc-1");
      }

      @Test
      @DisplayName("다른 문서의 유닛은 남는다")
      void emptiedDocumentKeepsOtherUnits() {
        // when
        List<ContextUnit> all = store.listAll(A);

        // then
        assertThat(ids(all)).containsExactly("u1", "u2", "u3", "u5");
      }
    }
  }
}
