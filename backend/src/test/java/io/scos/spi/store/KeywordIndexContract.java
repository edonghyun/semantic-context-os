package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 키워드 색인 포트 계약(CONTRACTS B3 순서 성질, TS search-contract-testkit `lexical` 블록의 Java 판). 점수 값은 보지 않는다.
 * EVENTUAL 어댑터는 {@link #awaitVisible()}로 반영을 기다린다(폴링 상한은 구현이 정한다).
 */
@DisplayName("키워드 색인은 질의 토큰을 포함하는 유닛을 적재 순서로 고른다(CONTRACTS B3)")
public abstract class KeywordIndexContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");

  /** 사례의 유닛 수보다 큰 top-k — 필터·순서 사례가 k에 잘리지 않는다. */
  static final int TOP_K = 10;

  /** PostgreSQL 어댑터가 문장 하나에 묶는 유닛 수(1,000)보다 큰 배치. */
  static final int LARGE_BATCH = 2_500;

  protected KeywordIndex index;

  protected abstract KeywordIndex newIndex();

  /** EVENTUAL 어댑터가 재정의한다 — upsert가 검색에 보일 때까지 기다린다. */
  protected void awaitVisible() {}

  @BeforeEach
  void setUpContract() {
    index = newIndex();
  }

  static IndexedText unit(String id, String doc, String title, String content) {
    return new IndexedText(id, doc, title, content, "/", false);
  }

  static IndexedText inPartition(String partition, IndexedText t) {
    return new IndexedText(
        t.unitId(), t.documentId(), t.title(), t.content(), partition, t.archived());
  }

  static IndexedText archived(IndexedText t) {
    return new IndexedText(t.unitId(), t.documentId(), t.title(), t.content(), t.partition(), true);
  }

  private List<String> ids(SpaceId space, String q, int k, SearchFilter f) {
    return index.search(space, q, k, f).stream().map(ScoredRef::unitId).toList();
  }

  @Nested
  @DisplayName("\"정산 재시도\"의 토큰을 0·1·2개 가진 유닛이 있을 때")
  class ZeroOneAndTwoTokens {
    @BeforeEach
    void givenUnitsWithZeroOneAndTwoTokens() {
      index.upsert(
          A,
          List.of(
              unit("none", "d0", "백업 정책", "스냅샷은 30일간 보관한다"),
              unit("one", "d1", "정산 배치", "정산 배치는 매일 자정에 실행된다"),
              unit("both", "d2", "정산 배치 재시도", "정산 배치 실패 시 재시도는 3회까지 허용된다")));
      awaitVisible();
    }

    @Test
    @DisplayName("질의 토큰을 더 많이 포함하는 유닛이 앞선다")
    void containingUnitsRankAboveOthers() {
      // when
      List<String> got = ids(A, "정산 재시도", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).startsWith("both");
    }

    @Test
    @DisplayName("질의 토큰이 없는 유닛은 결과에 없다")
    void unitsWithoutTokensAreAbsent() {
      // when
      List<String> got = ids(A, "정산 재시도", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).contains("one").doesNotContain("none");
    }
  }

  @Test
  @DisplayName("붙여쓰기 질의는 정확 표기 매치가 공백 소실 교정 매치보다 앞선다(T12.1)")
  void exactBeatsCompact() {
    // given: 2글자 토큰은 TS도 LIKE 경로라 공백 유무를 구분하지 않는다. T12.1의 불변식은 **질의 토큰이 붙여쓰기**("호출한도")일 때
    // 원문 "호출한도"(정확) > "호출 한도"(compact 교정)이다.
    index.upsert(
        A,
        List.of(
            unit("compact", "d1", "한도", "호출 한도를 초과하면 429가 반환된다"),
            unit("exact", "d2", "한도", "호출한도를 초과하면 차단된다")));
    awaitVisible();

    // when
    List<String> got = ids(A, "호출한도", TOP_K, SearchFilter.NONE);

    // then
    assertThat(got).containsExactly("exact", "compact");
  }

  @Nested
  @DisplayName("\"결제\" 유닛과 무관한 \"온보딩\" 유닛이 있을 때")
  class PaymentAndOnboarding {
    @BeforeEach
    void givenPaymentAndOnboarding() {
      index.upsert(
          A,
          List.of(
              unit("pay", "d1", "결제 모듈", "결제 모듈 마이그레이션은 Y 방식으로 확정했다"),
              unit("other", "d2", "온보딩", "신규 입사자는 첫날 계정을 발급받는다")));
      awaitVisible();
    }

    @Test
    @DisplayName("한국어 2글자 질의는 그 토큰을 포함하는 유닛만 찾는다")
    void twoCharKoreanQueryFinds() {
      // when
      List<String> got = ids(A, "결제", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("pay");
    }

    @Test
    @DisplayName("한국어 2글자 질의의 토큰이 어디에도 없으면 결과가 없다")
    void twoCharKoreanQueryMisses() {
      // given
      if (!ids(A, "입사", TOP_K, SearchFilter.NONE).contains("other"))
        throw new IllegalStateException("given 준비 실패: other 유닛이 색인돼 있어야 한다");

      // when
      List<String> got = ids(A, "권한", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).isEmpty();
    }
  }

  @Nested
  @DisplayName("bigram만 겹치는·경계를 넘는·토큰을 포함하는 유닛이 있을 때")
  class NearMisses {
    @BeforeEach
    void givenNearMisses() {
      index.upsert(
          A,
          List.of(
              unit(
                  "shares-bigram",
                  "d1",
                  "코드 리뷰 규칙",
                  "모든 PR은 팀 리드가 승인한다"), // "하이브리드"의 bigram "리드"만 공유
              unit("cross-boundary", "d2", "배포 절차", "배포 장애 회고"), // 공백 제거 "배포장애"에만 "포장"이 있다
              unit("contains", "d3", "검색 로드맵", "하이브리드 검색 도입")));
      awaitVisible();
    }

    @Test
    @DisplayName("bigram만 겹치는 유닛은 질의 토큰을 포함하지 않으므로 결과에 없다")
    void bigramOverlapIsNotAMatch() {
      // given
      if (!ids(A, "리드", TOP_K, SearchFilter.NONE).contains("shares-bigram"))
        throw new IllegalStateException("given 준비 실패: shares-bigram 유닛이 색인돼 있어야 한다");

      // when
      List<String> got = ids(A, "하이브리드", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("contains");
    }

    @Test
    @DisplayName("단어 경계를 넘는 2글자 매치는 결과에 없다")
    void crossBoundaryTwoCharIsNotAMatch() {
      // given
      if (!ids(A, "배포", TOP_K, SearchFilter.NONE).contains("cross-boundary"))
        throw new IllegalStateException("given 준비 실패: cross-boundary 유닛이 색인돼 있어야 한다");

      // when
      List<String> got = ids(A, "포장", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("3글자 이상 붙여쓰기 질의는 공백 소실 매치를 허용한다")
    void compactQueryMatchesAcrossSpaces() {
      // when
      List<String> got = ids(A, "하이브리드검색", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("contains");
    }
  }

  @Test
  @DisplayName("3글자 이상 토큰은 trigram 하나라도 공백 제거 본문에 있으면 정확 매치 뒤에 나온다(TS 등가)")
  void compactTrigramOrMatchesBehindExact() {
    // given
    index.upsert(
        A,
        List.of(
            unit("partial", "d1", "백업 정책", "스냅샷은 30일간 보관한다"), // ctext "스냅샷은30일간보관한다"에 trigram "스냅샷"
            unit("exact", "d2", "스냅샷보관 기간", "스냅샷보관 기간은 30일"),
            unit("none", "d3", "온보딩", "계정 발급 절차")));
    awaitVisible();

    // when·then
    assertThat(ids(A, "스냅샷보관", TOP_K, SearchFilter.NONE)).containsExactly("exact", "partial");
    assertThat(ids(A, "스냅샷보관 기간", TOP_K, SearchFilter.NONE)).containsExactly("exact", "partial");
  }

  @Test
  @DisplayName("질의의 유니코드 공백(NBSP·U+3000)도 토큰 경계다(CONTRACTS F-1)")
  void unicodeWhitespaceSplitsTokens() {
    // given
    index.upsert(
        A,
        List.of(
            unit("both", "d1", "결제 오류", "결제 오류 대응 절차"), unit("none", "d2", "온보딩", "신규 입사자 계정 발급")));
    awaitVisible();

    // when
    List<String> got = ids(A, "결제 오류　대응", TOP_K, SearchFilter.NONE);

    // then
    assertThat(got).containsExactly("both");
  }

  @Test
  @DisplayName("동점이면 먼저 적재한 유닛이 앞선다")
  void tiesByLoadOrder() {
    // given
    index.upsert(
        A,
        List.of(
            unit("z-first", "d1", "카나리 배포", "카나리 배포로 10%에 먼저 내보낸다"),
            unit("a-second", "d2", "카나리 배포", "카나리 배포로 10%에 먼저 내보낸다")));
    awaitVisible();

    // when
    List<String> got = ids(A, "카나리 배포", TOP_K, SearchFilter.NONE);

    // then
    assertThat(got).containsExactly("z-first", "a-second");
  }

  @Nested
  @DisplayName("같은 본문의 archived(/)·/rules·/ 유닛을 이 순서로 넣었을 때")
  class ArchivedAndPartitioned {
    /** 기본 점수 순서는 rules·root·arch가 아니라 적재 순서다. */
    @BeforeEach
    void givenArchivedAndPartitioned() {
      index.upsert(
          A,
          List.of(
              archived(unit("arch", "d1", "세션 만료", "세션 만료는 30분 무활동 기준")),
              inPartition("/rules", unit("rules", "d2", "세션 만료", "세션 만료는 30분 무활동 기준")),
              unit("root", "d3", "세션 만료", "세션 만료는 30분 무활동 기준")));
      awaitVisible();
    }

    @Test
    @DisplayName("archived 제외는 top-k 이전에 적용된다")
    void archivedFilterBeforeTopK() {
      // when
      List<String> got = ids(A, "세션 만료", 1, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("rules");
    }

    @Test
    @DisplayName("partition 필터는 top-k 이전에 적용된다")
    void partitionFilterBeforeTopK() {
      // when
      List<String> got = ids(A, "세션 만료", 1, new SearchFilter(false, List.of("/")));

      // then
      assertThat(got).containsExactly("root");
    }

    @Test
    @DisplayName("archived 포함 필터는 archived 유닛도 돌려준다")
    void includeArchived() {
      // when
      List<String> got = ids(A, "세션 만료", 3, new SearchFilter(true, null));

      // then
      assertThat(got).containsExactly("arch", "rules", "root");
    }
  }

  @Test
  @DisplayName("같은 unitId를 다시 넣으면 새 본문으로 찾고, 옛 본문으로는 찾지 못한다")
  void upsertReplaces() {
    // given
    index.upsert(A, List.of(unit("x", "d1", "인덱스 튜닝", "복합 인덱스를 추가했다")));

    // when
    index.upsert(A, List.of(unit("x", "d1", "백업 정책", "스냅샷은 30일간 보관한다")));
    awaitVisible();

    // then: 새 본문 매치는 지우기만 하는 구현을 거른다
    assertThat(ids(A, "스냅샷", TOP_K, SearchFilter.NONE)).containsExactly("x");
    assertThat(ids(A, "인덱스", TOP_K, SearchFilter.NONE)).isEmpty();
  }

  @Test
  @DisplayName("다시 넣은 유닛의 점수는 같은 본문을 처음 넣은 유닛과 같다")
  void replacedUnitScoresLikeFreshUnit() {
    // given: fresh를 새 본문으로 먼저 넣고, x를 옛 본문(질의 "스냅샷보관"의 bigram "보관"을 가진다) → fresh와 같은 새 본문으로 다시
    // 넣는다. 옛 본문의 색인 흔적이 남으면 x의 공백 소실 매치 점수가 fresh보다 커진다.
    index.upsert(A, List.of(unit("fresh", "d1", "백업", "스냅샷은 30일간 둔다")));
    index.upsert(A, List.of(unit("x", "d2", "백업", "보관 기간 정책")));
    index.upsert(A, List.of(unit("x", "d2", "백업", "스냅샷은 30일간 둔다")));
    awaitVisible();

    // when
    List<String> got = ids(A, "스냅샷보관", TOP_K, SearchFilter.NONE);

    // then
    assertThat(got).as("동점이면 먼저 적재한 fresh가 앞선다").containsExactly("fresh", "x");
  }

  @Nested
  @DisplayName("B에 x가 있는 상태에서 A에 같은 x를 넣고 새 본문으로 다시 넣으면")
  class SameUnitIdReplacedInAnotherSpace {
    @BeforeEach
    void givenSameUnitIdReplacedInAnotherSpace() {
      index.upsert(B, List.of(unit("x", "d1", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
      index.upsert(A, List.of(unit("x", "d2", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
      index.upsert(A, List.of(unit("x", "d2", "백업 정책", "스냅샷은 30일간 보관한다")));
      awaitVisible();
    }

    @Test
    @DisplayName("A에서는 다시 넣은 본문으로 찾는다")
    void replaceWithSameUnitIdInAnotherSpace() {
      // when
      List<String> got = ids(A, "스냅샷", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("x");
    }

    @Test
    @DisplayName("B의 결과는 그대로다")
    void replaceInAnotherSpaceKeepsThisSpace() {
      // when
      List<String> got = ids(B, "과금", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("x");
    }
  }

  @Test
  @DisplayName("빈 배치 upsert는 색인을 바꾸지 않는다")
  void emptyBatchChangesNothing() {
    // given
    index.upsert(A, List.of(unit("x", "d1", "백업 정책", "스냅샷은 30일간 보관한다")));

    // when
    index.upsert(A, List.of());
    awaitVisible();

    // then
    assertThat(ids(A, "스냅샷", TOP_K, SearchFilter.NONE)).containsExactly("x");
  }

  @Nested
  @DisplayName("한 배치에서 x(옛 본문) → y → x(y와 같은 새 본문) 순서로 넣으면")
  class UnitRepeatedInOneBatch {
    @BeforeEach
    void givenUnitRepeatedInOneBatch() {
      index.upsert(
          A,
          List.of(
              unit("x", "d1", "인덱스 튜닝", "복합 인덱스를 추가했다"),
              unit("y", "d2", "카나리 배포", "카나리 배포로 10%에 먼저 내보낸다"),
              unit("x", "d1", "카나리 배포", "카나리 배포로 10%에 먼저 내보낸다")));
      awaitVisible();
    }

    @Test
    @DisplayName("마지막 본문만 찾는다")
    void repeatedUnitInBatchKeepsLastText() {
      // given
      if (!ids(A, "카나리", TOP_K, SearchFilter.NONE).contains("x"))
        throw new IllegalStateException("given 준비 실패: 새 본문으로 x를 찾아야 한다");

      // when
      List<String> got = ids(A, "인덱스", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("적재 순서는 처음 자리다")
    void repeatedUnitInBatchKeepsFirstLoadOrder() {
      // when
      List<String> got = ids(A, "카나리 배포", TOP_K, SearchFilter.NONE);

      // then
      assertThat(got).containsExactly("x", "y");
    }
  }

  /** 같은 본문의 유닛 {@link #LARGE_BATCH}개를 사전순과 반대인 id로 한 배치에 넣고 그 id를 적재 순서로 돌려준다. */
  List<String> givenLargeBatchOfTies() {
    List<IndexedText> batch = new ArrayList<>();
    for (int i = LARGE_BATCH; i > 0; i--)
      batch.add(unit(String.format(Locale.ROOT, "u-%05d", i), "d" + i, "카나리 배포", "카나리 배포 절차"));
    index.upsert(A, batch);
    awaitVisible();
    return batch.stream().map(IndexedText::unitId).toList();
  }

  @Test
  @DisplayName("어댑터의 문장 묶음보다 큰 배치도 모두 적재 순서대로 찾는다")
  void largeBatchKeepsLoadOrder() {
    // given
    List<String> loadOrder = givenLargeBatchOfTies();

    // when
    List<String> got = ids(A, "카나리", LARGE_BATCH, SearchFilter.NONE);

    // then
    assertThat(got).containsExactlyElementsOf(loadOrder);
  }

  @Test
  @DisplayName("삭제한 유닛은 결과에서 사라진다")
  void deleteRemoves() {
    // given
    index.upsert(A, List.of(unit("x", "d1", "백업 정책", "스냅샷은 30일간 보관한다")));
    awaitVisible();
    if (!ids(A, "스냅샷", TOP_K, SearchFilter.NONE).equals(List.of("x")))
      throw new IllegalStateException("given 준비 실패: 삭제 전에는 x 하나를 찾아야 한다");

    // when
    index.delete(A, List.of("x"));
    awaitVisible();

    // then
    assertThat(ids(A, "스냅샷", TOP_K, SearchFilter.NONE)).isEmpty();
  }

  @Test
  @DisplayName("한 공간에서 삭제해도 다른 공간의 같은 unitId는 남는다")
  void deleteKeepsSameUnitIdInAnotherSpace() {
    // given
    index.upsert(A, List.of(unit("x", "d1", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
    index.upsert(B, List.of(unit("x", "d1", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
    awaitVisible();
    if (!ids(B, "과금", TOP_K, SearchFilter.NONE).equals(List.of("x")))
      throw new IllegalStateException("given 준비 실패: 삭제 전에는 B에서 x를 찾아야 한다");

    // when
    index.delete(A, List.of("x"));
    awaitVisible();

    // then
    assertThat(ids(B, "과금", TOP_K, SearchFilter.NONE)).containsExactly("x");
  }

  @Test
  @DisplayName("다른 공간의 유닛은 보이지 않는다")
  void spaceIsolation() {
    // given
    index.upsert(A, List.of(unit("mine", "d1", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
    index.upsert(B, List.of(unit("theirs", "d1", "과금 규정", "과금 내역은 매월 정산서와 대조한다")));
    awaitVisible();

    // when
    List<String> inA = ids(A, "과금", TOP_K, SearchFilter.NONE);
    List<String> inB = ids(B, "과금", TOP_K, SearchFilter.NONE);

    // then
    assertThat(inA).containsExactly("mine");
    assertThat(inB).containsExactly("theirs");
  }
}
