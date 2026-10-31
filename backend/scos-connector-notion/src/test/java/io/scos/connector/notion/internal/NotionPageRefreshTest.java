package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.ReconciliationDiscovery.Classification;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("페이지 갱신은 완료 버전과 안정된 본문을 함께 확인한다")
class NotionPageRefreshTest {
  final NotionRefreshScenario context = new NotionRefreshScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("roots config의 겹침을 누락 집계 유예까지 전달한다")
  void sourceOverlapControlsMissGrace() {
    context.oldMissWithinConfiguredOverlap();
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.RECENT);
  }

  @ParameterizedTest
  @EnumSource(NotionPageRefresh.Path.class)
  @DisplayName("full 열거와 같은 버전 INC 확인 모두 실제 확인 경로를 남긴다")
  void recordsSuccessfulReadingPath(NotionPageRefresh.Path path) {
    // given
    context.bodyAwaitingReading(path);
    // when
    context.collect(path);
    // then
    assertThat(context.lastReadingPath()).contains(path.name());
  }

  @Test
  @DisplayName("기존 세대의 새 하위 트리도 첫 본문 구축은 bootstrap이다")
  void newSubtreeStartsWithBootstrapBody() {
    // given
    context.newSubtreeInEstablishedGeneration();
    // when
    context.collectPage(CHILD_PAGE, NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.BOOTSTRAP);
  }

  @Test
  @DisplayName("새 DS의 전체 백필 시드만으로 증분 성공 이력을 만들지 않는다")
  void fullSeedIsNotSuccessfulIncrementalExploration() {
    // given
    context.pageBelongsToDataSourceWithOnlyFullSeed();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.BOOTSTRAP);
  }

  @Test
  @DisplayName("전체에서 직접 확인한 오래된 trash도 페이지 상태 누락으로 기록한다")
  void recordsExplicitTrashDifference() {
    // given
    context.oldPageWasMissedBySuccessfulTrashSearch();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.INCREMENTAL_MISS);
  }

  @Test
  @DisplayName("full 발견 뒤 동일 버전을 증분 확인해도 최초 누락 기록을 취소하지 않는다")
  void incrementalConfirmationPreservesMiss() {
    // given
    context.bodyAwaitingReading(NotionPageRefresh.Path.INCREMENTAL);
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.INCREMENTAL_MISS);
  }

  @Test
  @DisplayName("행의 비교 기회는 일반 검색보다 해당 DS의 성공 탐색을 따른다")
  void rowUsesItsOwnSuccessfulExploration() {
    // given
    context.pageBelongsToEstablishedDataSource();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.RECENT);
  }

  @Test
  @DisplayName("새 DS의 초기 행 백필은 다른 검색의 성공 이력이 있어도 bootstrap이다")
  void newDataSourceUsesItsOwnBaseline() {
    // given
    context.pageBelongsToNewDataSource();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.BOOTSTRAP);
  }

  @Test
  @DisplayName("기준 구축 뒤 직전 성공 탐색의 유예 경계 버전은 누락으로 기록한다")
  void recordsOldMissInEstablishedGeneration() {
    // given
    context.oldPageWasMissedBySuccessfulSearch();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.INCREMENTAL_MISS);
  }

  @Test
  @DisplayName("최초 전체 본문 반영은 페이지 상태 bootstrap 한 건으로 기록한다")
  void recordsBootstrapPageState() {
    // given
    context.emptyBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.discoveries()).containsExactly(Classification.BOOTSTRAP);
  }

  @Test
  @DisplayName("완료 결과는 child 인계를 확인할 본문 버전을 식별한다")
  void completedBodyIdentifiesItsVersion() {
    // given
    context.emptyBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(((NotionPageRefresh.Completed) context.result).version())
        .isEqualTo(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("같은 완료 버전의 증분 후보는 본문 API를 생략한다")
  void skipsCompletedVersion() {
    // given
    context.sameBodyIsCompleted();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.pageRequests()).isEqualTo(1);
    assertThat(context.bodyRequests()).isZero();
  }

  @Test
  @DisplayName("미완료 페이지는 빈 본문도 안정성 확인 뒤 완료한다")
  void completesPreviouslyUnfinishedBody() {
    // given
    context.emptyBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.completedVersion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("본문의 마지막 커서까지 읽어야 모든 블록을 완료한다")
  void completesAllBodyPages() {
    // given
    context.pagedBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.completedBlocks()).containsExactlyInAnyOrder("block:one", "block:two");
  }

  @Test
  @DisplayName("중첩된 일반 블록은 같은 페이지 본문으로 완료한다")
  void completesNestedBlocksInSameScope() {
    // given
    context.nestedBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.completedBlocks()).containsExactlyInAnyOrder("block:nested", "block:leaf");
  }

  @Test
  @DisplayName("child page는 부모 본문을 넘지 않고 독립 작업으로 발견한다")
  void discoversChildPageAsIndependentWork() {
    // given
    context.childPageAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.discovered).containsExactly(new NotionWork.Page(CHILD_PAGE));
    assertThat(context.completedBlocks()).containsExactly("block:child");
  }

  @Test
  @DisplayName("child database는 독립 컨테이너 초기화 작업으로 발견한다")
  void discoversChildDatabase() {
    // given
    context.childDatabaseAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.discovered).containsExactly(new NotionWork.Database("database"));
  }

  @Test
  @DisplayName("읽는 중 버전 변경은 정리 없이 최신 본문부터 다시 읽는다")
  void restartsChangedVersionWithoutPrematureCleanup() {
    // given
    context.pageChangesDuringBodyRead();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.completedVersion()).contains(NotionRefreshScenario.LATEST_VERSION);
    assertThat(context.cleanedBeforeLatestVersion).isFalse();
  }

  @Test
  @DisplayName("계속 편집되는 본문은 제한된 재시도 뒤 미완료로 남긴다")
  void limitsUnstableBodyRetries() {
    // given
    context.pageKeepsChanging();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
  }

  @Test
  @DisplayName("블록 응답과 다음 본문 커서를 같은 커밋에 남긴다")
  void commitsBodyCursorWithBlocks() {
    // given
    context.pagedBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.cursorCommittedWithFirstBlock()).isEqualTo("body-2");
  }

  @Test
  @DisplayName("본문 완료 직후 재기동해도 발견한 자식 작업을 잃지 않는다")
  void preservesChildHandoffAcrossRestart() {
    // given
    context.childPageAvailable();
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // when
    context.restartCompletedRead();
    // then
    assertThat(context.discovered).containsExactly(new NotionWork.Page(CHILD_PAGE));
    assertThat(context.bodyRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("부분 축소 보호로 완료가 보류되면 호출자에게 미완료를 돌려준다")
  void reportsGuardedBodyAsPending() {
    // given
    context.bodyShrinkIsGuarded();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
  }

  @Test
  @DisplayName("종료 조회에 다른 페이지가 오면 본문 완료 증거로 쓰지 않는다")
  void rejectsWrongPageAtFinish() {
    // given
    context.wrongPageAtFinish();
    // when·then
    assertThatThrownBy(() -> context.collect(NotionPageRefresh.Path.INCREMENTAL))
        .isInstanceOf(NotionProtocolException.class);
    assertThat(context.completedVersion()).isEmpty();
  }

  @Test
  @DisplayName("명시적 페이지 휴지통은 본문 없이 provider 삭제를 반영한다")
  void appliesExplicitTrashWithoutBody() {
    // given
    context.explicitTrashAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.pageIsDeleted()).isTrue();
    assertThat(context.bodyRequests()).isZero();
  }

  @Test
  @DisplayName("같은 세대 소속이 확인된 휴지통 페이지는 부모 없이 삭제한다")
  void appliesKnownTrashWithUnreadableParent() {
    // given
    context.knownTrashChildAvailable();
    // when
    context.collectPage(CHILD_PAGE, NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.childIsDeleted()).isTrue();
  }

  @Test
  @DisplayName("설정 루트가 아닌 페이지 404는 거부로 격리한다")
  void isolatesMissingChildPage() {
    // given
    context.missingChildAvailable();
    // when
    context.collectPage("missing", NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
    assertThat(context.rejectionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("종료 조회의 하위 페이지 404도 완료 없이 거부로 남긴다")
  void isolatesMissingChildAtFinish() {
    // given
    context.childDisappearsAtFinish();
    // when
    context.collectPage(CHILD_PAGE, NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
    assertThat(context.rejectionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 버전이어도 미완료 본문을 다시 읽는다")
  void rereadsSameVersionWhenIncomplete() {
    // given
    context.sameVersionBodyIsIncomplete();
    context.emptyBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.bodyRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("전체 경로는 같은 완료 버전이어도 본문을 읽는다")
  void fullPathRereadsCompletedVersion() {
    // given
    context.sameBodyIsCompleted();
    context.emptyBodyAvailable();
    // when
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.bodyRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("본문 거부를 빈 본문으로 완료하거나 정리하지 않는다")
  void rejectedBodyPreservesPreviousBlocks() {
    // given
    context.bodyIsNotFound();
    // when
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
    assertThat(context.ghostIsActive()).isTrue();
  }

  @Test
  @DisplayName("본문 항목 거부는 일반 미완료와 구분해 반환한다")
  void identifiesRejectedBodyAsUnfinishedRejection() {
    context.bodyIsNotFound();
    context.collect(NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.incompletionReason()).isEqualTo(NotionPageRefresh.Incompletion.REJECTED);
  }

  @Test
  @DisplayName("불완전한 블록 응답은 원문에 적용하지 않는다")
  void incompleteResponseDoesNotApplyBlocks() {
    // given
    context.bodyResponseIsIncomplete();
    // when·then
    assertThatThrownBy(() -> context.collect(NotionPageRefresh.Path.INCREMENTAL))
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure ->
                assertThat(failure.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
    assertThat(context.rawExists("block:unapplied")).isFalse();
  }

  @Test
  @DisplayName("메타데이터 커밋 뒤 취소는 본문 요청 전에 중단한다")
  void cancellationPreservesIncompleteBody() {
    // given
    context.cancelsAfterMetadataCommit();
    // when·then
    assertThatThrownBy(() -> context.collect(NotionPageRefresh.Path.INCREMENTAL))
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure -> assertThat(failure.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
    assertThat(context.bodyRequests()).isZero();
  }

  @Test
  @DisplayName("처음 보는 미확정 휴지통 ID의 tombstone을 만들지 않는다")
  void unverifiedTrashCreatesNoTombstone() {
    // given
    context.unknownTrashChildAvailable();
    // when
    context.collectPage(CHILD_PAGE, NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.rawExists(CHILD_PAGE)).isFalse();
    assertThat(context.result).isInstanceOf(NotionPageRefresh.Pending.class);
  }

  @Test
  @DisplayName("범위 밖 공유 페이지는 원문에 저장하지 않는다")
  void foreignPageCreatesNoRawItem() {
    // given
    context.foreignPageAvailable();
    // when
    context.collectPage("foreign", NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.rawExists("foreign")).isFalse();
  }

  @Test
  @DisplayName("범위 밖으로 확인된 기존 페이지를 증분이 삭제하지 않는다")
  void outsideMoveDoesNotDeleteExistingPage() {
    // given
    context.knownChildMovesOutside();
    // when
    context.collectPage(CHILD_PAGE, NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.childIsActive()).isTrue();
  }

  @Test
  @DisplayName("요청 UUID와 응답 UUID의 하이픈 표기가 달라도 같은 페이지 본문을 완료한다")
  void completesPageAcrossUuidRepresentations() {
    context.pageUuidAliasAvailable();
    context.collectPage(NotionRefreshScenario.COMPACT_PAGE_ID, NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.aliasedPageCompletion()).contains(NotionRefreshScenario.VERSION);
  }

  @Test
  @DisplayName("명시적 trash는 과거 본문의 자식 인계도 같은 확인점에서 종료한다")
  void trashClosesPersistedChildHandoff() {
    context.staleHandoffBeforeTrash();
    context.collect(NotionPageRefresh.Path.INCREMENTAL);
    // then
    assertThat(context.savedBodyHandoff()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(NotionPageRefresh.Path.class)
  @DisplayName("실제 metadata 조회의 새 버전은 해당 경로의 본문 거부 memo를 해제한다")
  void newMetadataClearsDeferredBody(NotionPageRefresh.Path path) {
    context.newMetadataAfterDeferredBody(path);
    context.collect(path);
    // then
    assertThat(context.bodyDeferred(path)).isFalse();
  }
}
