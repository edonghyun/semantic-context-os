package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AclStore;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 유닛 저장소 포트 계약(CONTRACTS A1·D·E).
 *
 * <p>매 테스트의 준비에서 newStore()를 먼저 호출해 새 저장 상태를 만든 뒤 maintenance()를 호출한다. 유지보수 훅은 먼저 만든 읽기 포트와 같은 상태를
 * 공유하는 어댑터를 반환해야 한다.
 */
@DisplayName("유닛 저장소는 공간별로 유닛·문서·링크를 적재 순서로 둔다(CONTRACTS A1·D·E)")
public abstract class UnitStoreContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  static final Range ALL = new Range(0, 10);
  static final String LATEST_IN_D1 = "2026-10-03T00:00:00.000Z";
  static final String CAPTURED_IN_A = "2026-10-01T00:00:00.000Z";

  protected UnitStore store;
  protected UnitMaintenance maintenance;

  protected abstract UnitStore newStore();

  protected abstract AclStore aclStore();

  protected abstract UnitMaintenance maintenance();

  @BeforeEach
  void setUpContract() {
    store = newStore();
    maintenance = maintenance();
  }

  /** 기본 유닛(제목 "제목 <id>", 적재 시각 기본값). 차이가 있는 유닛은 {@link ContextUnits#unit}로 직접 만든다. */
  protected static ContextUnit unit(String id, String doc) {
    return ContextUnits.unit(id).inDocument(doc).build();
  }

  static List<String> ids(List<ContextUnit> units) {
    return units.stream().map(ContextUnit::id).toList();
  }

  List<String> idsOf(SpaceId space, String document) {
    return ids(store.listByDocument(space, AccessScope.singleUser(), document, ALL));
  }

  @Nested
  @DisplayName("허용·다른 키·UNKNOWN·FAILED·UNSUPPORTED·ACL 없는 문서가 있을 때")
  class AccessReads {
    UnitAccessScenario access;

    @BeforeEach
    void givenDocuments() {
      access = new UnitAccessScenario(store, maintenance, aclStore(), A);
      access.givenDocuments();
      access.requireReadableDocuments();
    }

    @ParameterizedTest
    @ValueSource(strings = {"d2", "d3", "d4", "d6"})
    @DisplayName("제한 listByDocument는 막힌 문서를 빈 목록으로 돌려준다")
    void hidesBlockedDocument(String document) {
      // when
      var got = store.listByDocument(A, UnitAccessScenario.ALICE, document, ALL);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("제한 getDocuments는 허용 문서만 반환한다")
    void getDocumentsOnlyVisibleDocuments() {
      // when
      var got = store.getDocuments(A, UnitAccessScenario.ALICE, UnitAccessScenario.DOCUMENTS);

      // then
      assertThat(got).extracting(DocumentInfo::documentId).containsExactly("d1");
    }

    @Test
    @DisplayName("빈 키의 getDocuments는 비어 있다")
    void getDocumentsNoKeys() {
      // when
      var got = store.getDocuments(A, UnitAccessScenario.NO_KEYS, UnitAccessScenario.DOCUMENTS);

      // then
      assertThat(got).extracting(DocumentInfo::documentId).isEmpty();
    }

    @Test
    @DisplayName("getDocuments는 다른 공간의 ACL을 쓰지 않는다")
    void getDocumentsOtherSpace() {
      access.givenAclOnlyInOtherSpace(B);

      // when
      var got = store.getDocuments(A, UnitAccessScenario.ALICE, UnitAccessScenario.DOCUMENTS);

      // then
      assertThat(got).extracting(DocumentInfo::documentId).isEmpty();
    }

    @Test
    @DisplayName("무제한 getDocuments는 ACL과 관계없이 기존 결과다")
    void getDocumentsUnrestricted() {
      // when
      var got = store.getDocuments(A, AccessScope.singleUser(), UnitAccessScenario.DOCUMENTS);

      // then
      assertThat(got)
          .extracting(DocumentInfo::documentId)
          .containsExactly("d6", "d1", "d2", "d3", "d4", "d5");
    }

    @Test
    @DisplayName("getDocuments는 UNSUPPORTED 문서를 scope 키로 읽는다")
    void getDocumentsUnsupported() {
      // when
      var got = store.getDocuments(A, UnitAccessScenario.ORG, UnitAccessScenario.DOCUMENTS);

      // then
      assertThat(got).extracting(DocumentInfo::documentId).containsExactly("d5");
    }

    @Test
    @DisplayName("제한 linkedUnits는 허용 문서만 반환한다")
    void linkedUnitsOnlyVisibleDocuments() {
      // when
      var got = store.linkedUnits(A, UnitAccessScenario.ALICE, List.of("u1"));

      // then
      assertThat(got).extracting(LinkedUnit::unitId).containsExactly("u1b");
    }

    @Test
    @DisplayName("빈 키의 linkedUnits는 비어 있다")
    void linkedUnitsNoKeys() {
      // when
      var got = store.linkedUnits(A, UnitAccessScenario.NO_KEYS, List.of("u1"));

      // then
      assertThat(got).extracting(LinkedUnit::unitId).isEmpty();
    }

    @Test
    @DisplayName("linkedUnits는 다른 공간의 ACL을 쓰지 않는다")
    void linkedUnitsOtherSpace() {
      access.givenAclOnlyInOtherSpace(B);

      // when
      var got = store.linkedUnits(A, UnitAccessScenario.ALICE, List.of("u1"));

      // then
      assertThat(got).extracting(LinkedUnit::unitId).isEmpty();
    }

    @Test
    @DisplayName("무제한 linkedUnits는 ACL과 관계없이 기존 결과다")
    void linkedUnitsUnrestricted() {
      // when
      var got = store.linkedUnits(A, AccessScope.singleUser(), List.of("u1"));

      // then
      assertThat(got)
          .extracting(LinkedUnit::unitId)
          .containsExactly("u1b", "u2", "u3", "u4", "u5", "u6");
    }

    @Test
    @DisplayName("linkedUnits는 UNSUPPORTED 문서를 scope 키로 읽는다")
    void linkedUnitsUnsupported() {
      // when
      var got = store.linkedUnits(A, UnitAccessScenario.ORG, List.of("u1"));

      // then
      assertThat(got).extracting(LinkedUnit::unitId).containsExactly("u5");
    }

    @Test
    @DisplayName("제한 listByDocument는 허용 문서의 페이지를 적재 순서로 읽는다")
    void pagesVisibleDocument() {
      // when
      var got = store.listByDocument(A, UnitAccessScenario.ALICE, "d1", new Range(1, 1));

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("u1b");
    }

    @Test
    @DisplayName("제한 페이지는 압축 유닛을 offset과 limit에 세지 않는다(a11 I-1)")
    void pagesExcludeCompactedUnits() {
      access.givenMixedTiers();

      var got = store.listByDocument(A, UnitAccessScenario.ALICE, "mixed", new Range(1, 2));

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("raw-second", "raw-third");
    }

    @Test
    @DisplayName("무제한 페이지는 압축 유닛도 offset과 limit에 센다")
    void unrestrictedPagesIncludeCompactedUnits() {
      access.givenMixedTiers();

      var got = store.listByDocument(A, AccessScope.singleUser(), "mixed", new Range(1, 2));

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("raw-first", "summary-middle");
    }

    @Test
    @DisplayName("UNSUPPORTED 문서는 scope 키로 본문을 읽는다")
    void readsUnsupportedDocument() {
      // when
      var got = store.listByDocument(A, UnitAccessScenario.ORG, "d5", ALL);

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("u5");
    }

    @Test
    @DisplayName("빈 키의 listByDocument는 비어 있다")
    void listByDocumentNoKeys() {
      // when
      var got = store.listByDocument(A, UnitAccessScenario.NO_KEYS, "d1", ALL);

      // then
      assertThat(got).extracting(ContextUnit::id).isEmpty();
    }

    @Test
    @DisplayName("listByDocument는 다른 공간의 ACL을 쓰지 않는다")
    void listByDocumentOtherSpace() {
      access.givenAclOnlyInOtherSpace(B);

      // when
      var got = store.listByDocument(A, UnitAccessScenario.ALICE, "d1", ALL);

      // then
      assertThat(got).extracting(ContextUnit::id).isEmpty();
    }

    @Test
    @DisplayName("무제한 listByDocument는 ACL과 관계없이 기존 결과다")
    void listByDocumentUnrestricted() {
      // when
      var got = store.listByDocument(A, AccessScope.singleUser(), "d1", ALL);

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("u1", "u1b");
    }

    @Test
    @DisplayName("빈 키의 getMany는 비어 있다")
    void getManyNoKeys() {
      // when
      var got = store.getMany(A, UnitAccessScenario.NO_KEYS, UnitAccessScenario.IDS);

      // then
      assertThat(got).extracting(ContextUnit::id).isEmpty();
    }

    @Test
    @DisplayName("getMany는 다른 공간의 ACL을 쓰지 않는다")
    void getManyOtherSpace() {
      access.givenAclOnlyInOtherSpace(B);

      // when
      var got = store.getMany(A, UnitAccessScenario.ALICE, UnitAccessScenario.IDS);

      // then
      assertThat(got).extracting(ContextUnit::id).isEmpty();
    }

    @Test
    @DisplayName("무제한 getMany는 ACL과 관계없이 기존 결과다")
    void getManyUnrestricted() {
      // when
      var got = store.getMany(A, AccessScope.singleUser(), UnitAccessScenario.IDS);

      // then
      assertThat(got)
          .extracting(ContextUnit::id)
          .containsExactly("u6", "u1b", "u2", "u3", "u4", "u5", "u1", "u1b");
    }

    @Test
    @DisplayName("getMany는 UNSUPPORTED 문서를 scope 키로 읽는다")
    void getManyUnsupported() {
      // when
      var got = store.getMany(A, UnitAccessScenario.ORG, UnitAccessScenario.IDS);

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("u5");
    }

    @Test
    @DisplayName("제한 getMany는 허용 유닛만 입력 순서와 중복을 유지한다")
    void readsOnlyVisibleUnitsInRequestedOrder() {
      // when
      var got = store.getMany(A, UnitAccessScenario.ALICE, UnitAccessScenario.IDS);

      // then
      assertThat(got).extracting(ContextUnit::id).containsExactly("u1b", "u1", "u1b");
    }
  }

  @Test
  @DisplayName("getMany는 있는 유닛을 입력 순서대로 나온 자리마다 돌려준다")
  void getManyOrderMissingDuplicates() {
    // given
    maintenance.upsert(
        A,
        List.of(
            unit("u1", "d1"),
            unit("u2", "d1"),
            ContextUnits.unit("u3").inDocument("d2").titled("제목 u3").build()));

    // when
    List<ContextUnit> got =
        store.getMany(A, AccessScope.singleUser(), List.of("u3", "missing", "u1", "u3"));

    // then
    assertThat(ids(got)).containsExactly("u3", "u1", "u3");
    assertThat(got.get(0).title()).isEqualTo("제목 u3");
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
    @DisplayName("listByDocument는 적재 순서대로 range 구간을 돌려준다")
    void listByDocumentRange() {
      // when·then
      assertThat(ids(store.listByDocument(A, AccessScope.singleUser(), "d1", new Range(0, 2))))
          .containsExactly("b", "a");
      assertThat(ids(store.listByDocument(A, AccessScope.singleUser(), "d1", new Range(1, 10))))
          .containsExactly("a", "c");
    }

    @Test
    @DisplayName("문서 끝을 넘는 offset은 빈 목록이다")
    void listByDocumentPastEnd() {
      // when
      List<ContextUnit> got =
          store.listByDocument(A, AccessScope.singleUser(), "d1", new Range(5, 10));

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("없는 문서는 빈 목록이다")
    void listByUnknownDocument() {
      // when
      List<ContextUnit> got = store.listByDocument(A, AccessScope.singleUser(), "nope", ALL);

      // then
      assertThat(got).isEmpty();
    }
  }

  @Test
  @DisplayName("linkedUnits는 입력 밖 링크 대상 유닛을 적재 순서로 한 번씩 돌려준다")
  void linkedUnits() {
    // given
    maintenance.upsert(
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
    List<LinkedUnit> got =
        store.linkedUnits(A, AccessScope.singleUser(), List.of("hub1", "hub2", "t1"));

    // then: t1·hub1·hub2는 입력이라 제외(t1 → hub 링크의 대상이어도), 대상 유닛 적재 순서 t2 < o1
    assertThat(got).containsExactly(new LinkedUnit("t2", "target"), new LinkedUnit("o1", "other"));
  }

  @Test
  @DisplayName("링크가 없는 유닛의 linkedUnits는 비어 있다")
  void noLinks() {
    // given
    maintenance.upsert(A, List.of(ContextUnits.unit("n1").inDocument("none").titled("n1").build()));

    // when
    List<LinkedUnit> got = store.linkedUnits(A, AccessScope.singleUser(), List.of("n1"));

    // then
    assertThat(got).isEmpty();
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
    @DisplayName("문서 정보는 있는 문서만 요청 순서로 돌려준다")
    void documentsInRequestOrder() {
      // when
      List<DocumentInfo> infos =
          store.getDocuments(A, AccessScope.singleUser(), List.of("d2", "missing", "d1"));

      // then
      assertThat(infos).extracting(DocumentInfo::documentId).containsExactly("d2", "d1");
    }

    @Test
    @DisplayName("문서 정보는 첫 유닛의 제목·uri·출처와 유닛 수·capturedAt 최댓값이다")
    void documentInfoFields() {
      // when
      DocumentInfo d1 = store.getDocuments(A, AccessScope.singleUser(), List.of("d1")).get(0);

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
    @DisplayName("문서의 latestCapturedAt은 코드 유닛 문자열의 최댓값이다")
    void latestCapturedAtIsCodeUnitMax() {
      // when
      Optional<String> latest =
          store.getDocuments(A, AccessScope.singleUser(), List.of("d1")).get(0).latestCapturedAt();

      // then: d1 안에서는 '.'(0x2E) < 'Z'(0x5A)라 "…00Z"가 최댓값이다
      assertThat(latest).contains("2026-10-03T00:00:00Z");
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
    @DisplayName("같은 유닛 id라도 공간마다 따로 조회된다")
    void getManyIsPerSpace() {
      // when
      List<ContextUnit> got = store.getMany(B, AccessScope.singleUser(), List.of("u1", "u2"));

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
      assertThat(store.linkedUnits(A, AccessScope.singleUser(), List.of("u1")))
          .containsExactly(new LinkedUnit("u2", "d2"));
      assertThat(store.linkedUnits(B, AccessScope.singleUser(), List.of("u1")))
          .containsExactly(new LinkedUnit("u9", "b-d2"));
    }

    @Test
    @DisplayName("문서 정보는 자기 공간 것만이다")
    void documentInfoIsPerSpace() {
      // when
      List<DocumentInfo> infos =
          store.getDocuments(A, AccessScope.singleUser(), List.of("d1", "b-d1"));

      // then
      assertThat(infos).extracting(DocumentInfo::title).containsExactly("A");
    }
  }
}
