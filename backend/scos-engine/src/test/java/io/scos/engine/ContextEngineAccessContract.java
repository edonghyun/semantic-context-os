package io.scos.engine;

import static io.scos.engine.AccessScenario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("엔진은 요청자가 볼 수 있는 문서만 어느 출구로도 내보낸다")
abstract class ContextEngineAccessContract {
  protected abstract AccessScenario scenario();

  @Nested
  @DisplayName("검색은 요청자의 접근 키로 후보를 거른다")
  class Search {
    @Test
    @DisplayName("Alice는 공개 그룹과 지정 원천의 문서를 본다")
    void aliceSeesPublicAndDesignated() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(ALICE, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsOnly(PUBLIC, DESIGNATED);
    }

    @Test
    @DisplayName("링크 신규 유입은 보이는 대상만 들인다")
    void linksIntroduceOnlyVisibleTargets() {
      // given
      var scenario = scenario();
      var query = scenario.givenLinkStart();
      // when
      var result = scenario.restricted().search(ALICE, query);
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsOnly(PUBLIC, DESIGNATED);
    }

    @Test
    @DisplayName("Bob은 비공개 그룹과 지정 원천의 문서를 본다")
    void bobSeesPrivateAndDesignated() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(BOB, scenario.searchQuery());
      // then
      assertThat(result.hits())
          .extracting(ContextHit::documentId)
          .containsOnly(PRIVATE, DESIGNATED);
    }

    @Test
    @DisplayName("비활성 사용자는 문서를 보지 못한다")
    void inactiveUserSeesNothing() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(CAROL, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).isEmpty();
    }

    @Test
    @DisplayName("미등록 사용자는 문서를 보지 못한다")
    void unregisteredUserSeesNothing() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(DAVE, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).isEmpty();
    }

    @Test
    @DisplayName("연결 없는 사용자는 지정 원천만 본다")
    void unlinkedUserSeesDesignated() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(ERIN, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsOnly(DESIGNATED);
    }

    @Test
    @DisplayName("게스트 신원은 그룹 문서를 보지 못한다")
    void guestSeesDesignated() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().search(GINA, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsOnly(DESIGNATED);
    }

    @Test
    @DisplayName("검색 결과와 점수는 보이는 코퍼스만 검색한 값과 같다")
    void scoresMatchVisibleCorpus() {
      // given
      var scenario = scenario();
      var expected = scenario.visibleOnly(ALICE).search(ALICE, scenario.searchQuery());
      // when
      var result = scenario.restricted().search(ALICE, scenario.searchQuery());
      // then
      assertThat(result.hits()).isEqualTo(expected.hits());
    }

    @Test
    @DisplayName("current로 검색해도 차단된 최신 유닛은 후보에 들지 않는다")
    void currentExcludesBlockedNewestUnit() {
      // given
      var scenario = scenario();
      var query = new SearchQuery(Optional.empty(), QUERY, 3, Effort.LOW, Profile.CURRENT);
      // when
      var result = scenario.restricted().search(ALICE, query);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("p1", "p2", "s1");
    }

    @Test
    @DisplayName("current로 검색하면 허용된 최신 유닛이 먼저 나온다")
    void currentOrdersVisibleUnits() {
      // given
      var scenario = scenario();
      var query = new SearchQuery(Optional.empty(), QUERY, 3, Effort.LOW, Profile.CURRENT);
      // when
      var result = scenario.restricted().search(BOB, query);
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).containsExactly("q1", "q2", "s1");
    }

    @Test
    @DisplayName("단일 사용자는 ACL 상태와 관계없이 모든 문서를 찾는다")
    void unrestrictedSeesEveryDocument() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.unrestricted().search(ALICE, scenario.searchQuery());
      // then
      assertThat(result.hits())
          .extracting(ContextHit::documentId)
          .containsOnly(PUBLIC, PRIVATE, UNKNOWN, FAILED, NO_ACL, DESIGNATED);
    }
  }

  @Nested
  @DisplayName("문서 읽기는 보이는 문서에서만 페이지를 연다")
  class Read {

    @Test
    @DisplayName("보이는 문서는 저장된 본문을 적재 순서로 읽는다")
    void visiblePagePreservesUnits() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().read(ALICE, ReadRequest.of(PUBLIC));
      // then
      assertThat(result.units())
          .containsExactly(
              new UnitView("p1", QUERY + " " + PUBLIC, QUERY),
              new UnitView("p2", QUERY + " " + PUBLIC, QUERY));
    }

    @ParameterizedTest
    @ValueSource(strings = {PRIVATE, UNKNOWN, FAILED, NO_ACL, MISSING})
    @DisplayName("막힌 문서는 없는 코퍼스와 같은 예외와 문구다")
    void blockedReadMatchesAbsent(String document) {
      // given
      var scenario = scenario();
      var missing = scenario.absentRead(document, 0);
      // when·then
      assertThatThrownBy(() -> scenario.restricted().read(ALICE, ReadRequest.of(document)))
          .isExactlyInstanceOf(missing.getClass())
          .hasMessage(missing.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {PRIVATE, UNKNOWN, FAILED, NO_ACL, MISSING})
    @DisplayName("끝을 넘는 offset도 막힌 문서이면 부재 오류다")
    void blockedOffsetMatchesAbsent(String document) {
      // given
      var scenario = scenario();
      var missing = scenario.absentRead(document, BEYOND_END);
      // when·then
      assertThatThrownBy(
              () -> scenario.restricted().read(ALICE, scenario.readAt(document, BEYOND_END)))
          .isExactlyInstanceOf(missing.getClass())
          .hasMessage(missing.getMessage());
    }
  }

  @Nested
  @DisplayName("관련 문서는 보이는 시드와 대상만 사용한다")
  class Related {

    @ParameterizedTest
    @ValueSource(strings = {PRIVATE, UNKNOWN, FAILED, NO_ACL, MISSING})
    @DisplayName("막힌 시드는 없는 코퍼스와 같은 예외와 문구다")
    void blockedSeedMatchesAbsent(String document) {
      // given
      var scenario = scenario();
      var missing = scenario.absentRelated(document);
      // when·then
      assertThatThrownBy(() -> scenario.restricted().related(ALICE, RelatedQuery.of(document)))
          .isExactlyInstanceOf(missing.getClass())
          .hasMessage(missing.getMessage());
    }

    @Test
    @DisplayName("관련 문서의 점수와 채널은 보이는 코퍼스와 같다")
    void channelsMatchVisibleCorpus() {
      // given
      var scenario = scenario();
      var expected = scenario.givenVisibleRelated();
      // when
      var result = scenario.restricted().related(ALICE, RelatedQuery.of(PUBLIC));
      // then
      assertThat(result).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("조립은 보이는 후보만 예산에 넣는다")
  class Assembly {
    @Test
    @DisplayName("항목과 점수는 보이는 코퍼스를 조립한 값과 같다")
    void itemsMatchVisibleCorpus() {
      // given
      var scenario = scenario();
      var expected = scenario.visibleOnly(ALICE).assemble(ALICE, AssembleQuery.of(QUERY));
      // when
      var result = scenario.restricted().assemble(ALICE, AssembleQuery.of(QUERY));
      // then
      assertThat(result.items()).isEqualTo(expected.items());
    }

    @Test
    @DisplayName("토큰 사용량과 예산 초과는 보이는 코퍼스와 같다")
    void budgetMatchesVisibleCorpus() {
      // given
      var scenario = scenario();
      var query = new AssembleQuery(Optional.empty(), QUERY, SMALL_BUDGET);
      var expected = scenario.givenTruncatedVisibleBundle(query);
      // when
      var result = scenario.restricted().assemble(ALICE, query);
      // then
      assertThat(result)
          .extracting(ContextBundle::usedTokens, ContextBundle::truncated)
          .containsExactly(expected.usedTokens(), expected.truncated());
    }
  }

  @Nested
  @DisplayName("최신성과 공간도 요청 범위를 따른다")
  class Scope {
    @Test
    @DisplayName("최신성은 숨은 문서 시각이 아닌 원천 수집 시각이다")
    void freshnessUsesVisibleSources() {
      // given
      var scenario = scenario();
      // when
      var result = scenario.restricted().freshness(ALICE, SPACE);
      // then
      assertThat(result.latestCapturedAt()).contains(COLLECTED.toString());
    }

    @Test
    @DisplayName("다른 공간의 같은 문서 id는 그 공간 ACL로 막는다")
    void otherSpaceIsDenied() {
      // given
      var scenario = scenario().withOtherSpace();
      // when
      var result = scenario.restricted().search(ALICE, scenario.otherSpaceQuery());
      // then
      assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("다른 공간의 같은 문서 id도 그 공간 권한이면 보인다")
    void otherSpaceIsVisibleToBob() {
      // given
      var scenario = scenario().withOtherSpace();
      // when
      var result = scenario.restricted().search(BOB, scenario.otherSpaceQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsExactly(PUBLIC);
    }
  }

  @Nested
  @DisplayName("압축 유닛이 보이는 문서에 속한 상태에서")
  class Compacted {
    @Test
    @DisplayName("압축만 있는 허용 문서의 제한 읽기는 빈 페이지다")
    void restrictedReadOfOnlySummariesIsEmpty() {
      // given
      var scenario = scenario();
      scenario.givenOnlyCompactedSeeds();
      // when
      var result = scenario.restricted().read(ALICE, ReadRequest.of(PUBLIC));
      // then
      assertThat(result.units()).isEmpty();
    }

    @Test
    @DisplayName("압축만 있는 허용 문서에는 다음 읽기 페이지가 없다")
    void restrictedReadOfOnlySummariesHasNoNextOffset() {
      // given
      var scenario = scenario();
      scenario.givenOnlyCompactedSeeds();
      // when
      var result = scenario.restricted().read(ALICE, ReadRequest.of(PUBLIC));
      // then
      assertThat(result.nextOffset()).isEmpty();
    }

    @Test
    @DisplayName("압축만 있는 관련 문서 시드는 없는 문서와 같은 오류다")
    void restrictedRelatedOfOnlySummariesIsAbsent() {
      // given
      var scenario = scenario();
      scenario.givenOnlyCompactedSeeds();
      var missing = new DocumentNotFoundException(PUBLIC);
      // when·then
      assertThatThrownBy(() -> scenario.restricted().related(ALICE, RelatedQuery.of(PUBLIC)))
          .isExactlyInstanceOf(missing.getClass())
          .hasMessage(missing.getMessage());
    }

    @Test
    @DisplayName("단일 사용자 관련 문서는 압축 시드의 임베딩 채널을 유지한다")
    void unrestrictedRelatedKeepsSummarySeeds() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSeedPrefix();
      // when
      var result = scenario.unrestricted().related(ALICE, RelatedQuery.of(PUBLIC));
      // then
      assertThat(result.documents())
          .filteredOn(d -> d.documentId().equals(DESIGNATED))
          .extracting(RelatedDocument::via)
          .containsExactly(RelatedDocument.Via.EMBEDDING);
    }

    @Test
    @DisplayName("관련 문서는 압축을 뺀 앞 다섯 원본의 채널만 사용한다")
    void relatedUsesFiveOriginalSeeds() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSeedPrefix();
      // when
      var result = scenario.restricted().related(ALICE, RelatedQuery.of(PUBLIC));
      // then
      assertThat(result.documents())
          .extracting(RelatedDocument::via)
          .containsExactly(RelatedDocument.Via.LINK);
    }

    @Test
    @DisplayName("제한 읽기는 압축 뒤 원본이 남으면 다음 offset을 돌려준다")
    void restrictedNextOffsetCountsOriginals() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var request = new ReadRequest(Optional.empty(), PUBLIC, 0, ORIGINALS_BEFORE_SUMMARY);
      // when
      var result = scenario.restricted().read(ALICE, request);
      // then
      assertThat(result.nextOffset()).hasValue(ORIGINALS_BEFORE_SUMMARY);
    }

    @Test
    @DisplayName("제한 읽기는 압축을 건너뛰어 마지막 원본 페이지를 채운다")
    void restrictedLastPageContainsOriginal() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var request =
          new ReadRequest(Optional.empty(), PUBLIC, ORIGINALS_BEFORE_SUMMARY, SINGLE_UNIT_LIMIT);
      // when
      var result = scenario.restricted().read(ALICE, request);
      // then
      assertThat(result.units()).extracting(UnitView::unitId).containsExactly("drill-only");
    }

    @Test
    @DisplayName("제한 읽기의 마지막 원본 뒤에는 다음 offset이 없다")
    void restrictedLastPageHasNoNextOffset() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var request =
          new ReadRequest(Optional.empty(), PUBLIC, ORIGINALS_BEFORE_SUMMARY, SINGLE_UNIT_LIMIT);
      // when
      var result = scenario.restricted().read(ALICE, request);
      // then
      assertThat(result.nextOffset()).isEmpty();
    }

    @Test
    @DisplayName("단일 사용자 읽기는 압축 요약도 적재 순서로 돌려준다")
    void unrestrictedReadKeepsSummary() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      // when
      var result = scenario.unrestricted().read(ALICE, ReadRequest.of(PUBLIC));
      // then
      assertThat(result.units())
          .extracting(UnitView::unitId)
          .containsExactly("p1", "p2", COMPACTED_ID, "drill-only");
    }

    @Test
    @DisplayName("압축 제외 후 끝에 도달한 offset은 빈 페이지다")
    void restrictedOffsetBeyondOriginalsIsEmpty() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var request =
          new ReadRequest(Optional.empty(), PUBLIC, ORIGINALS_WITH_DRILL_DOWN, SINGLE_UNIT_LIMIT);
      // when
      var result = scenario.restricted().read(ALICE, request);
      // then
      assertThat(result.units()).isEmpty();
    }

    @Test
    @DisplayName("제한 읽기는 압축 요약의 본문을 내보내지 않는다")
    void restrictedReadOmitsSummary() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      // when
      var result = scenario.restricted().read(ALICE, ReadRequest.of(PUBLIC));
      // then
      assertThat(result.units())
          .extracting(UnitView::unitId)
          .containsExactly("p1", "p2", "drill-only");
    }

    @Test
    @DisplayName("제한 조립은 요약 본문과 드릴다운 원본을 내보내지 않는다")
    void restrictedAssemblyOmitsSummaryAndDrillDown() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      // when
      var result = scenario.restricted().assemble(ALICE, AssembleQuery.of(QUERY));
      // then
      assertThat(result.items())
          .extracting(BundleItem::unitId)
          .doesNotContain(COMPACTED_ID, "drill-only");
      assertThat(result.items()).extracting(BundleItem::documentId).contains(PUBLIC);
    }

    @Test
    @DisplayName("링크로 신규 유입하는 압축 유닛도 제외한다")
    void linkedSummaryIsExcluded() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var query = scenario.givenSummaryLinkStart();
      // when
      var result = scenario.restricted().search(ALICE, query);
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .contains("p1")
          .doesNotContain(COMPACTED_ID);
    }

    @Test
    @DisplayName("제한 검색은 요약 본문을 후보로 내보내지 않는다")
    void restrictedSearchOmitsSummary() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      // when
      var result = scenario.restricted().search(ALICE, scenario.searchQuery());
      // then
      assertThat(result.hits())
          .extracting(ContextHit::unitId)
          .contains("p1")
          .doesNotContain(COMPACTED_ID);
    }

    @Test
    @DisplayName("단일 사용자는 같은 요약을 검색 후보로 받는다")
    void unrestrictedSearchKeepsSummary() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      // when
      var result = scenario.unrestricted().search(ALICE, scenario.searchQuery());
      // then
      assertThat(result.hits()).extracting(ContextHit::unitId).contains(COMPACTED_ID);
    }

    @Test
    @DisplayName("단일 사용자 조립은 같은 요약에서 원본 드릴다운을 한다")
    void unrestrictedAssemblyKeepsDrillDown() {
      // given
      var scenario = scenario();
      scenario.givenCompactedSummary();
      var query = new AssembleQuery(Optional.empty(), QUERY, AssembleQuery.MAX_TOKEN_BUDGET);
      // when
      var result = scenario.unrestricted().assemble(ALICE, query);
      // then
      assertThat(result.items())
          .extracting(BundleItem::unitId)
          .contains(COMPACTED_ID, "drill-only");
    }
  }
}
