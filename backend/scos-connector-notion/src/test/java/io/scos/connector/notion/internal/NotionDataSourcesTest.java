package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("data source 재조정은 완전한 행 집합과 독립 확인점을 보장한다")
class NotionDataSourcesTest {
  final NotionDataSourcesScenario context = new NotionDataSourcesScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("범위 미확정은 거부 memo가 아니어서 다음 조각에서 소속을 재검사한다")
  void unknownScopeCanRecoverInSameRun() {
    context.dataSourceScopeBecomesKnown();
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
  }

  @Test
  @DisplayName("본문이 거부된 행도 같은 run의 query가 새 버전을 발견하면 반영한다")
  void newRowVersionRetriesRejectedBody() {
    context.newVersionAfterRowBodyRejection();
    context.collect();
    // then
    assertThat(context.completedRowVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("목록 완료 필드가 없던 구 v4의 마지막 행 진행도 이전 생성 시각부터 재탐색한다")
  void legacyTerminalRowScanRestartsCreationOrdering() {
    context.legacyTerminalRowScanWasResumed();
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
  }

  @Test
  @DisplayName("거부 행이 남아도 같은 run의 다음 증분 조각에서 새 행 편집을 찾는다")
  void nextSliceStillFindsNewRowEdit() {
    context.newRowEditAppearsAfterRejectedSlice();
    context.collect();
    // then
    assertThat(context.completedRowVersion())
        .contains(NotionIncrementalScenario.NEXT_EXPLORATION.toString());
  }

  @Test
  @DisplayName("404 database의 소속 DS도 폴링 inventory에서 퇴역한다")
  void missingDatabaseRetiresItsDataSources() {
    context.knownDatabaseWasDeleted();
    context.poll();
    // then
    assertThat(context.deletedDataSourceIsInInventory()).isFalse();
  }

  @Test
  @DisplayName("재개한 DB 목록에서 빠진 DS는 inventory에서 퇴역한다")
  void detachedDataSourceRetiresFromInventory() {
    context.dataSourceWasRemovedFromDatabase();
    context.poll();
    // then
    assertThat(context.deletedDataSourceIsInInventory()).isFalse();
  }

  @Test
  @DisplayName("404 data source는 raw 삭제 없이 폴링 inventory에서 퇴역한다")
  void missingDataSourceRetiresFromInventory() {
    context.knownDataSourceWasDeleted();
    context.poll();
    // then
    assertThat(context.deletedDataSourceIsInInventory()).isFalse();
  }

  @Test
  @DisplayName("거부 행이 남은 다음 run도 새 행 변경을 수집한다")
  void rejectedRowScanDoesNotHideNewEdits() {
    context.rejectedRowScanWasResumed();
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
  }

  @Test
  @DisplayName("DS trash와 INC 진행 종료는 같은 확인점 커밋에 보존한다")
  void trashedDataSourceCommitsRetiredProgress() {
    // given
    context.pendingDataSourceWasTrashed();
    // when
    context.poll();
    // then
    assertThat(context.committedRowPaths()).containsExactly(NotionPageRefresh.Path.FULL);
  }

  @Test
  @DisplayName("휴지통으로 확인한 DS는 미완료 INC 행만 닫고 FULL 진행을 보존한다")
  void trashedDataSourceRetiresOnlyIncrementalRows() {
    // given
    context.pendingDataSourceWasTrashed();
    // when
    context.poll();
    // then
    assertThat(context.pendingRowPaths()).containsExactly(NotionPageRefresh.Path.FULL);
  }

  @Test
  @DisplayName("범위 밖으로 확인한 data source는 증분 대기만 닫고 전체 진행은 보존한다")
  void outsideDataSourceRetiresOnlyIncrementalRows() {
    // given
    context.pendingDataSourceMovedOutside();
    // when
    context.poll();
    // then
    assertThat(context.pendingRowPaths()).containsExactly(NotionPageRefresh.Path.FULL);
  }

  @Test
  @DisplayName("W 없는 기존 data source의 증분 점검은 오래된 행부터 백필한다")
  void knownDataSourceWithoutWatermarkBackfillsOldRows() {
    // given
    context.knownDataSourceWithoutWatermark();
    // when
    context.poll();
    // then
    assertThat(context.completedOldRow()).isTrue();
    assertThat(context.firstQuery().has("filter")).isFalse();
  }

  @Test
  @DisplayName("한 data source의 행 목록이 거부되어도 다른 source를 완료한다")
  void rejectedQueryDoesNotBlockOtherSources() {
    // given
    context.rejectedQueryBeforeAccessibleSource();
    // when
    context.collect();
    // then
    assertThat(context.collectedRows()).contains("second-old-row");
  }

  @Test
  @DisplayName("한 data source가 거부되어도 다른 data source의 행은 완료한다")
  void rejectedDataSourceDoesNotBlockOtherSources() {
    // given
    context.rejectedDataSourceBeforeAccessibleSource();
    // when
    context.collect();
    // then
    assertThat(context.collectedRows()).contains("second-old-row");
  }

  @Test
  @DisplayName("새 data source는 소스 검색 하한보다 오래된 행도 백필한다")
  void newDataSourceBackfillsOldRows() {
    // given
    context.oldRowInNewDataSource();
    // when
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
    assertThat(context.firstQuery().has("filter")).isFalse();
  }

  @Test
  @DisplayName("한 행의 본문이 거부되어도 접근 가능한 다른 행은 완료한다")
  void rejectedRowDoesNotBlockOtherRows() {
    // given
    context.rejectedRowBeforeAccessibleRow();
    // when
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
  }

  @Test
  @DisplayName("query cap 뒤 생성 시각 창을 이어 모든 행을 수집한다")
  void splitsCappedRowsByCreatedTime() {
    // given
    context.cappedAcrossWindows();
    // when
    context.collect();
    // then
    assertThat(context.collectedRows())
        .containsExactlyInAnyOrder("first-row", "boundary-row", "last-row");
  }

  @Test
  @DisplayName("재기동한 기존 data source는 자기 확인점의 겹침 하한으로 읽는다")
  void resumedDataSourceUsesOwnWatermark() {
    // given
    context.completedDataSourceWasRestarted();
    // when
    context.collect();
    // then
    assertThat(context.lastQuery().at("/filter/last_edited_time/on_or_after").asString())
        .isEqualTo("2026-10-24T23:30:00Z");
  }

  @Test
  @DisplayName("생성 시각 창의 경계 중복 행은 한 번만 갱신한다")
  void mergesBoundaryRowAcrossWindows() {
    // given
    context.cappedAcrossWindows();
    // when
    context.collect();
    // then
    assertThat(context.pageRequests("boundary-row")).isEqualTo(2);
  }

  @Test
  @DisplayName("재개한 행 응답의 미완료 본문을 빈 다음 응답으로 덮지 않는다")
  void resumesPendingRowBody() {
    // given
    context.pendingRowBody();
    // when
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
  }

  @Test
  @DisplayName("같은 생성 시각의 cap이 반복되면 확인점을 확정하지 않는다")
  void refusesUnsplitableCreationWindow() {
    // given
    context.unadvancingCreationWindow();
    // when·then
    assertThatThrownBy(context::collect)
        .isInstanceOf(NotionProtocolException.class)
        .hasMessageContaining("window cannot advance");
    assertThat(context.dataSourceWatermark()).isEmpty();
  }

  @Test
  @DisplayName("새 data source 백필 뒤 기준 시각의 겹침 탐색을 즉시 수행한다")
  void catchesEditsDuringInitialBackfill() {
    // given
    context.oldRowInNewDataSource();
    // when
    context.collect();
    // then
    assertThat(context.lastQuery().at("/filter/last_edited_time/on_or_after").asString())
        .isEqualTo("2026-10-24T23:30:00Z");
  }

  @Test
  @DisplayName("부모 페이지 신호 없이 기존 DB의 새 data source를 백필한다")
  void pollsDatabaseInventoryForNewDataSource() {
    // given
    context.completedDataSourceWasRestarted();
    context.addedDataSourceToDatabase();
    // when
    context.poll();
    // then
    assertThat(context.collectedRows()).contains("second-old-row");
  }

  @Test
  @DisplayName("생성 시각 정렬이 깨진 응답은 행 본문 적용 전에 거부한다")
  void rejectsUnorderedRowsBeforeApplying() {
    // given
    context.unorderedRows();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(NotionProtocolException.class);
    assertThat(context.collectedRows()).isEmpty();
  }

  @Test
  @DisplayName("필수 data source의 현재 소속이 미확정이면 성공으로 마감하지 않는다")
  void unknownDataSourceScopeCannotSucceed() {
    // given
    context.unreadableDataSourceParent();
    // when
    var completed = context.collect();
    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("행 목록의 커서 순환은 확인점 전진 없이 실패한다")
  void rejectsCyclicRowsCursor() {
    // given
    context.cyclicRowsCursor();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.dataSourceWatermark()).isEmpty();
  }

  @Test
  @DisplayName("행 커서 순환의 실패 메시지는 창 기반 목록을 식별한다")
  void cyclicRowsNamesWindowedWalk() {
    context.cyclicRowsCursor();

    // when·then
    assertThatThrownBy(context::collect)
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining(
            "cursor cycle: list=windowed-rows:" + NotionDataSourcesScenario.DATA_SOURCE);
  }

  @Test
  @DisplayName("같은 run의 증분이 이미 읽은 행도 전체 경로는 본문을 다시 검증한다")
  void fullStillReadsIncrementallyCollectedRows() {
    // given
    context.oldRowInNewDataSource();
    context.collect();
    // when
    context.collectFull();
    // then
    assertThat(context.bodyRequests(NotionDataSourcesScenario.OLD_ROW)).isEqualTo(2);
  }

  @Test
  @DisplayName("위키 결과의 중첩 data source도 독립 백필한다")
  void initializesNestedWikiDataSource() {
    // given
    context.nestedWikiDataSource();
    // when
    context.collect();
    // then
    assertThat(context.nestedWatermark()).contains(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("다른 data source의 실패는 먼저 성공한 확인점을 되돌리지 않는다")
  void preservesSuccessfulDataSourceOnLaterFailure() {
    // given
    context.secondDataSourceFails();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.dataSourceWatermark()).contains(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("만료된 행 커서는 같은 고정 하한으로 다시 탐색한다")
  void restartsExpiredRowsCursor() {
    // given
    context.expiredRowsCursor();
    // when
    context.collect();
    // then
    assertThat(context.completedOldRow()).isTrue();
    assertThat(context.dataSourceWatermark()).contains(context.restartedScanStart());
  }

  @Test
  @DisplayName("전체가 소유한 data source의 증분 후보는 완료로 넘긴다")
  void fullOwnedDataSourceCandidateIsComplete() {
    context.state.beginFullRows(NotionDataSourcesScenario.DATA_SOURCE);
    var completed =
        context.collectCandidate(new NotionWork.DataSource(NotionDataSourcesScenario.DATA_SOURCE));

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("한 번 거부된 페이지 후보는 같은 경로에서 완료로 인정하지 않는다")
  void deferredPageCandidateRemainsIncomplete() {
    var page = new NotionWork.Page(NotionRefreshScenario.PAGE);
    context.state.defer(page, NotionPageRefresh.Path.INCREMENTAL);
    var completed = context.collectCandidate(page);

    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("컨테이너 후보의 전체 행 수집이 성공하면 완료를 반환한다")
  void containerCandidateReturnsCompletion() {
    context.oldRowInNewDataSource();
    var completed =
        context.collectCandidate(new NotionWork.Database(NotionDataSourcesScenario.DATABASE));

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("현재 범위 밖 database는 하위 탐색 없이 완료한다")
  void outsideDatabaseCompletes() {
    context.databaseMovedOutside();
    var completed = context.collect();

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("현재 범위 밖 data source는 증분 작업을 완료한다")
  void outsideDataSourceCompletes() {
    context.pendingDataSourceMovedOutside();
    var completed = context.poll();

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("하위 data source 거부는 database 완료로 숨기지 않는다")
  void rejectedChildLeavesDatabaseIncomplete() {
    context.rejectedDataSourceBeforeAccessibleSource();
    var completed = context.collect();

    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("새 수집 객체도 같은 run의 database 거부를 다시 호출하지 않는다")
  void rejectedDatabaseIsDeferredAcrossCollectors() {
    context.rejectedDatabase();
    context.collect();
    context.restartCollector();
    context.collect();

    // then
    assertThat(context.metadataRequests(NotionDataSourcesScenario.DATABASE)).isEqualTo(1);
  }

  @Test
  @DisplayName("완료한 페이지의 재수집은 완료를 반환한다")
  void completedPageReturnsCompletion() {
    context.data.emptyBodyAvailable();
    var page = new NotionWork.Page(NotionRefreshScenario.PAGE);
    context.collectWork(page, NotionPageRefresh.Path.INCREMENTAL);
    var completed = context.collectWork(page, NotionPageRefresh.Path.INCREMENTAL);

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("완료한 검색 페이지 후보도 재호출 시 완료를 반환한다")
  void completedCandidateReturnsCompletion() {
    context.data.emptyBodyAvailable();
    var page = new NotionWork.Page(NotionRefreshScenario.PAGE);
    context.collectCandidate(page);
    var completed = context.collectCandidate(page);

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("거부된 행 본문이 남으면 data source 탐색은 미완료다")
  void rejectedRowsReturnIncomplete() {
    context.rejectedRowBeforeAccessibleRow();
    var completed = context.collect();

    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("미확정 소속의 database는 탐색 완료로 반환하지 않는다")
  void unknownDatabaseScopeReturnsIncomplete() {
    context.databaseParentIsUnknown();
    var completed = context.collect();
    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("같은 run에서 보류한 컨테이너 후보는 완료로 반환하지 않는다")
  void deferredContainerCandidateReturnsIncomplete() {
    var work = new NotionWork.Database(NotionDataSourcesScenario.DATABASE);
    context.state.defer(work, NotionPageRefresh.Path.INCREMENTAL);
    var completed = context.collectCandidate(work);
    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("범위 밖 DS의 증분 진행 종료도 확인점에 커밋한다")
  void outsideDataSourceCommitsRetiredProgress() {
    context.pendingDataSourceMovedOutside();
    context.poll();
    // then
    assertThat(context.committedRowPaths()).containsExactly(NotionPageRefresh.Path.FULL);
  }

  @Test
  @DisplayName("database 조회 거부는 run의 항목 거부로 남긴다")
  void rejectedDatabaseIsReportedToRun() {
    context.rejectedDatabase();
    context.collect();
    // then
    assertThat(context.data.rejectionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("초기 백필 뒤 겹침 탐색이 처음 발견한 행도 본문까지 수집한다")
  void overlapCollectsNewlyDiscoveredRowBody() {
    context.overlapDiscoversNewRowAfterBackfill();
    context.collect();
    // then
    assertThat(context.collectedRows()).contains(NotionDataSourcesScenario.OVERLAP_ROW);
  }

  @Test
  @DisplayName("초기 백필 뒤 겹침 탐색의 행 본문 거부를 완료로 숨기지 않는다")
  void overlapRejectionLeavesDataSourceIncomplete() {
    context.overlapFindsRejectedRowBody();
    var completed = context.collect();
    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("같은 조각에서 미확정인 컨테이너를 다시 읽어도 완료로 바꾸지 않는다")
  void cachedUnknownContainerRemainsIncomplete() {
    context.databaseParentIsUnknown();
    context.collect();
    var completed = context.collect();
    // then
    assertThat(completed).isFalse();
  }
}
