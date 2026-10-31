package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("roots 증분은 확인점·범위·본문 완료로 탐색한다")
class NotionIncrementalTest {
  // 각 탐색은 새 커서 수신과 만료 응답을 포함한다: 최초 한 번 + 재시작 세 번.
  static final int REQUESTS_THROUGH_FOURTH_EXPIRY = 8;
  final NotionIncrementalScenario context = new NotionIncrementalScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.PendingCandidate.class)
  @DisplayName("해소되지 않는 UNKNOWN·404 이월은 이후 시작해 완주한 전체 순회 뒤 버린다")
  void laterFullRunDiscardsUnresolvedCandidate(
      NotionIncrementalScenario.PendingCandidate candidate) {
    context.unresolvedCandidateOutsideOverlap(candidate);
    context.startLaterFullRun();

    context.collectFull();
    // then
    assertThat(context.carriedCandidates()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.PendingCandidate.class)
  @DisplayName("이월 전에 시작된 전체 순회가 완료돼도 후보를 버리지 않는다")
  void earlierFullRunRetainsCandidate(NotionIncrementalScenario.PendingCandidate candidate) {
    context.unresolvedCandidateDuringSameFullRun(candidate);

    context.collectFull();
    // then
    assertThat(context.carriedCandidates()).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.PendingCandidate.class)
  @DisplayName("이후 시작한 전체도 재기동으로 중간 재개되면 이월 후보를 버리지 않는다")
  void resumedFullRunRetainsCandidate(NotionIncrementalScenario.PendingCandidate candidate) {
    context.unresolvedCandidateOutsideOverlap(candidate);
    context.startLaterFullRun();
    context.restartFullRun();

    context.collectFull();
    // then
    assertThat(context.carriedCandidates()).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.PendingCandidate.class)
  @DisplayName("구 v4 이월 후보도 재기동 뒤 새 전체 순회가 시작하기 전까지 유지한다")
  void legacyRetrySurvivesUpgrade(NotionIncrementalScenario.PendingCandidate candidate) {
    context.unresolvedCandidateOutsideOverlap(candidate);

    context.restartFromLegacyCheckpoint();
    // then
    assertThat(context.retryPageIds()).hasSize(1);
  }

  @Test
  @DisplayName("Search 하한 밖으로 지난 이월 후보도 재기동 뒤 접근이 회복되면 반영한다")
  void carriedCandidateSurvivesOverlapAndRestart() {
    context.retryOutsideOverlapBecomesReadable();
    context.collect();
    // then
    assertThat(context.completedUnknownVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.DirectRejection.class)
  @DisplayName("직접 발견한 자식이 거부돼도 Search에서 새 버전을 발견하면 반영한다")
  void searchedNewChildVersionRetriesDirectRejection(
      NotionIncrementalScenario.DirectRejection rejected) {
    context.newSearchVersionAfterDirectChildRejection(rejected);
    context.collect();
    // then
    assertThat(context.completedChildVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @ParameterizedTest
  @EnumSource(NotionIncrementalScenario.PendingCandidate.class)
  @DisplayName("미확정·거부 후보가 남은 다음 run도 Search 하한이 전진한다")
  void pendingCandidateCannotFreezeNextSearch(
      NotionIncrementalScenario.PendingCandidate candidate) {
    context.previousRunHasPendingCandidate(candidate);
    context.collect();
    // then
    assertThat(context.nextSearchLowerBound())
        .isEqualTo(NotionIncrementalScenario.NEXT_LOWER_BOUND);
  }

  @Test
  @DisplayName("본문이 거부된 페이지도 같은 run에서 새 버전으로 발견되면 반영한다")
  void newPageVersionRetriesRejectedBody() {
    context.newVersionAfterBodyRejection();
    context.collect();
    // then
    assertThat(context.completedRootVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("자식이 거부된 페이지도 같은 run의 새 버전은 반영한다")
  void newParentVersionIsCollectedAfterChildRejection() {
    context.newParentVersionAfterRejectedChild();
    context.collect();
    // then
    assertThat(context.completedRootVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("거부가 남아도 같은 run의 다음 증분 조각에서 새 편집을 찾는다")
  void nextSliceStillFindsNewEdit() {
    context.newEditAppearsAfterRejectedSlice();
    context.collect();
    // then
    assertThat(context.completedRootVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("부모가 미공유인 후보를 보류하고 다른 페이지 변경을 완료한다")
  void unknownCandidateDoesNotFailExploration() {
    context.unknownCandidateBeforeRoot();
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("재시작 확인점 직후 중단해도 Search를 처음부터 재열거한다")
  void interruptedRestartStillListsCandidates() {
    context.restartedSearchWasInterrupted();
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("같은 run의 다음 조각은 이미 거부한 후보를 재시도하지 않는다")
  void rejectedCandidateIsAttemptedOncePerRun() {
    context.rejectedScanWasResumed();
    context.collectTwiceInSameRun();
    // then
    assertThat(context.rejectedPageRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("거부 후보가 남은 다음 run도 새 검색 변경을 수집한다")
  void rejectedScanDoesNotHideNewEdits() {
    context.rejectedScanWasResumed();
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("보류 중이던 DS의 현재 trash 확인 뒤 내부 증분 due를 정상 확정한다")
  void trashedPendingDataSourceAllowsSuccessfulExploration() {
    // given
    context.pendingDataSourceIsTrashed();
    // when
    context.collect();
    // then
    assertThat(context.nextIncrementalAt()).isEqualTo(NotionIncrementalScenario.NEXT_EXPLORATION);
  }

  @Test
  @DisplayName("직전 성공 시각보다 오래된 지연 후보도 다음 겹침 탐색에서 수집한다")
  void collectsLateIndexedPageInsideOverlap() {
    // given
    context.lateCandidateAppearsAfterWatermark();
    // when
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionIncrementalScenario.LATE_VERSION);
  }

  @Test
  @DisplayName("미래 편집 시각을 수집해도 W는 탐색 시작까지만 전진한다")
  void futureEditCannotAdvanceWatermarkPastStart() {
    // given
    context.futureCandidateAvailable();
    // when
    context.collect();
    // then
    assertThat(context.activeWatermark()).contains(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("부모의 중첩 본문이 거부되어도 이미 발견한 child page는 완료한다")
  void rejectedParentDoesNotBlockDiscoveredChild() {
    // given
    context.rejectedParentHasAccessibleChild();
    // when
    context.collect();
    // then
    assertThat(context.completedChild()).isTrue();
  }

  @Test
  @DisplayName("반복 검색된 부모의 거부 container는 재시도 작업으로 이월한다")
  void repeatedParentRetainsRejectedContainer() {
    // given
    context.repeatedParentHasRejectedDatabase();
    // when
    context.collect();
    // then
    assertThat(context.retryPageIds()).containsExactly(NotionRefreshScenario.PAGE);
  }

  @Test
  @DisplayName("container 거부가 있으면 소스의 다음 증분 시각을 전진시키지 않는다")
  void rejectedContainerCannotPostponeIncremental() {
    // given
    context.knownContainerIsRejected();
    // when
    context.collect();
    // then
    assertThat(context.nextIncrementalAt()).isEqualTo(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("반복 검색된 부모의 거부 child는 재시도 작업으로 이월한다")
  void repeatedParentRetainsRejectedChild() {
    // given
    context.repeatedParentHasRejectedChild();
    // when
    context.collect();
    // then
    assertThat(context.retryPageIds()).containsExactly(NotionRefreshScenario.PAGE);
  }

  @Test
  @DisplayName("검색 본문 거부는 완료로 버리지 않고 재시도 작업으로 이월한다")
  void rejectedSearchWorkCannotAdvanceWatermark() {
    // given
    context.rejectedCandidateBeforeRoot();
    // when
    context.collect();
    // then
    assertThat(context.retryPageIds()).containsExactly(NotionIncrementalScenario.REJECTED_PAGE);
  }

  @Test
  @DisplayName("하한보다 오래된 검색 결과에서 다음 API 페이지를 읽지 않는다")
  void stopsBeforeNextPageAtOldBoundary() {
    // given
    context.oldBoundaryHasAnotherApiPage();
    // when
    context.collect();
    // then
    assertThat(context.activeSearchRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("하한과 같은 편집 시각의 페이지도 수집한다")
  void includesBoundaryTimestamp() {
    // given
    context.boundaryCandidateAvailable();
    // when
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("같은 경계 시각의 후보는 여러 API 페이지에서도 잘라내지 않는다")
  void readsEqualTimestampAcrossPages() {
    // given
    context.equalTimestampSpansApiPages();
    // when
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
    assertThat(context.activeSearchRequests()).isEqualTo(2);
  }

  @Test
  @DisplayName("여러 검색 응답의 같은 페이지 후보는 한 번만 갱신한다")
  void mergesRepeatedPageCandidates() {
    // given
    context.candidateRepeatsAcrossPages();
    // when
    context.collect();
    // then
    assertThat(context.rootMetadataRequests()).isEqualTo(2);
  }

  @Test
  @DisplayName("경계보다 오래된 응답도 검색 갈래가 다르면 확정하지 않는다")
  void wrongPhaseCannotAdvanceWatermark() {
    // given
    context.wrongPhaseBeforeBoundary();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(NotionProtocolException.class);
    assertThat(context.activeWatermark()).contains(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("하한 뒤에 최신 후보가 섞인 응답은 원본 적용 전에 거부한다")
  void unorderedResponseCannotApplyCandidates() {
    // given
    context.unorderedResponse();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(NotionProtocolException.class);
    assertThat(context.rootMetadataRequests()).isZero();
  }

  @Test
  @DisplayName("Search 마지막 응답의 미완료 본문은 재조회 없이 먼저 재개한다")
  void resumesPendingCandidateBeforeSearch() {
    // given
    context.pendingLastCandidate();
    // when
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
    assertThat(context.activeSearchRequests()).isZero();
  }

  @Test
  @DisplayName("만료된 Search 커서는 같은 하한에서 처음부터 다시 탐색한다")
  void restartsExpiredCursor() {
    // given
    context.expiredSearchCursor();
    // when
    context.collect();
    // then
    assertThat(context.completedRootVersion()).contains(NotionRefreshScenario.VERSION);
    assertThat(context.activeWatermark()).contains(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("오래된 응답의 incomplete도 적용과 워터마크 전진을 막는다")
  void incompleteCannotAdvanceWatermark() {
    // given
    context.incompleteOldResponse();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.activeWatermark()).contains(NotionIncrementalScenario.START);
    assertThat(context.rootMetadataRequests()).isZero();
  }

  @Test
  @DisplayName("커서와 무관한 요청 오류는 처음부터 재탐색하지 않는다")
  void otherValidationErrorCannotRestartSearch() {
    // given
    context.unrelatedValidationError();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.activeSearchRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("Search에 없는 child database의 오래된 행도 본문 발견으로 백필한다")
  void backfillsDatabaseDiscoveredInBody() {
    // given
    context.childDatabaseOutsideSearch();
    // when
    context.collect();
    // then
    assertThat(context.completedDiscoveredRow()).isTrue();
  }

  @Test
  @DisplayName("Search에 없는 child page를 본문에서 발견해 수집한다")
  void collectsChildOutsideSearch() {
    // given
    context.childPageOutsideSearch();
    // when
    context.collect();
    // then
    assertThat(context.completedChild()).isTrue();
  }

  @Test
  @DisplayName("모든 증분 경로가 성공한 뒤에만 다음 내부 탐색을 예약한다")
  void schedulesNextAfterSuccessfulExploration() {
    // given
    context.emptySuccessfulExploration();
    // when
    context.collect();
    // then
    assertThat(context.nextIncrementalAt()).isEqualTo(NotionIncrementalScenario.NEXT_EXPLORATION);
  }

  @Test
  @DisplayName("Search 커서 만료는 세 번 재시도한 뒤 전파한다")
  void repeatedExpiryStopsAfterThreeRestarts() {
    context.repeatedlyExpiredSearchCursor();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(NotionCursorExpired.class);
    assertThat(context.activeSearchRequests()).isEqualTo(REQUESTS_THROUGH_FOURTH_EXPIRY);
  }

  @Test
  @DisplayName("Search 재시작 뒤 요청이 실패해도 만료 커서를 확인점에 남기지 않는다")
  void failedRestartCannotRestoreExpiredSearchCursor() {
    context.searchFailsAfterCursorRestart();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedActiveCursor()).isEmpty();
  }

  @Test
  @DisplayName("다음 Search 요청이 실패해도 완료한 후보를 재개 대기열에 남기지 않는다")
  void failedNextSearchPreservesCompletedCandidate() {
    context.searchFailsAfterCompletedCandidate();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedActiveWork()).isEmpty();
  }
}
