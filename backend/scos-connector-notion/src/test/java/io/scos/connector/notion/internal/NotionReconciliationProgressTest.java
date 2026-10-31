package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionCheckpointScenario.SEARCH_FINISHED;
import static io.scos.connector.notion.internal.NotionCheckpointScenario.START;
import static io.scos.connector.notion.internal.NotionCheckpointScenario.fresh;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("재조정 진행은 다른 작업의 상태를 보존한다")
class NotionReconciliationProgressTest {
  static final String DATABASE = "database";
  static final String OTHER_DATABASE = "other-database";
  static final String DATA_SOURCE = "data-source";
  static final String OTHER_SOURCE = "other-source";
  static final NotionWork.Page FIRST = new NotionWork.Page("first");
  static final NotionWork.Page SECOND = new NotionWork.Page("second");
  static final NotionPageRefresh.Path INCREMENTAL = NotionPageRefresh.Path.INCREMENTAL;
  final NotionReconciliationState state = fresh();

  @Test
  @DisplayName("전체가 소유한 행 대기는 증분 예정 시각을 앞당기지 않는다")
  void fullOwnedRowsDoNotTriggerEarlyIncremental() {
    scheduleNextIncremental();
    pendingRows(DATA_SOURCE);
    state.beginFullRows(DATA_SOURCE);

    // then
    assertThat(state.incrementalDue(SEARCH_FINISHED)).isFalse();
  }

  @Test
  @DisplayName("전체 소유권 없는 행 대기는 증분 예정 시각 전에 재개한다")
  void pendingRowsResumeBeforeNextDue() {
    scheduleNextIncremental();
    pendingRows(DATA_SOURCE);

    // then
    assertThat(state.incrementalDue(SEARCH_FINISHED)).isTrue();
  }

  @Test
  @DisplayName("전체 경로만 진행 중이면 증분 예정 시각을 앞당기지 않는다")
  void fullScanDoesNotTriggerEarlyIncremental() {
    scheduleNextIncremental();
    state.rememberDataSource(DATA_SOURCE, START);
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, START);

    // then
    assertThat(state.incrementalDue(SEARCH_FINISHED)).isFalse();
  }

  @Test
  @DisplayName("미완료 행이 있으면 증분 성공으로 예약을 전진시키지 않는다")
  void pendingRowsBlockIncrementalSuccess() {
    state.beginIncremental(START);
    pendingRows(DATA_SOURCE);

    // when·then
    assertThatThrownBy(() -> state.incrementalSucceeded(START))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("한 검색 후보의 완료는 다른 이월 후보를 지우지 않는다")
  void completingOneRetryPreservesOtherRetry() {
    retryTwoCandidates();
    state.retrySearchWork(SearchPhase.ACTIVE, SECOND);
    state.completedSearchWork(SearchPhase.ACTIVE, FIRST);
    state.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);

    // then
    assertThat(state.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).work())
        .containsExactly(SECOND);
  }

  @Test
  @DisplayName("이미 이월한 후보를 다시 거부해도 다른 후보를 중복 등록하지 않는다")
  void retryingOneCandidateDoesNotDuplicateIt() {
    retryTwoCandidates();
    state.retrySearchWork(SearchPhase.ACTIVE, FIRST);
    state.retrySearchWork(SearchPhase.ACTIVE, SECOND);
    state.retrySearchWork(SearchPhase.ACTIVE, SECOND);
    state.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);

    // then
    assertThat(state.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).work())
        .containsExactly(FIRST, SECOND);
  }

  @Test
  @DisplayName("완료된 검색에 본문 대기가 없으면 재기동 시 목록 완료를 보존한다")
  void terminalEmptySearchRemainsCompleteAfterRestart() {
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of());
    var resumed = resume();

    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).listingComplete()).isTrue();
  }

  @Test
  @DisplayName("완료된 검색의 본문 대기는 재기동하면 새 시각부터 목록을 재탐색한다")
  void terminalSearchWithWorkRestartsAtCurrentTime() {
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of(FIRST));
    var resumed = resume();

    // then
    assertThat(resumed.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED).startedAt())
        .isEqualTo(SEARCH_FINISHED);
  }

  @Test
  @DisplayName("진행 중 행 커서는 재기동 시 현재 시각으로 덮지 않는다")
  void unfinishedRowsPreserveTheirStart() {
    pendingRows(DATA_SOURCE);
    state.rowsPage(
        DATA_SOURCE, INCREMENTAL, Optional.of("cursor"), Optional.empty(), false, List.of());
    var resumed = resume();

    // then
    assertThat(resumed.rows(DATA_SOURCE, INCREMENTAL).startedAt()).isEqualTo(START);
  }

  @Test
  @DisplayName("서로 다른 버전의 본문 인계는 재기동 후 이전 완료 자식을 보존한다")
  void restartPreservesHandoffAlongsideNewEnumeration() {
    state.bodyFinished("root", START.toString(), List.of(FIRST));
    state.recordBodyProgress(
        "root",
        SEARCH_FINISHED.toString(),
        List.of(new NotionWork.BlockChildren("root", Optional.empty())));
    var resumed = resume();

    // then
    assertThat(resumed.bodyHandoff("root", START.toString())).containsExactly(FIRST);
  }

  @Test
  @DisplayName("DB 퇴역은 다른 DB에 연결된 data source를 보존한다")
  void retiringDatabasePreservesOtherDatabaseChildren() {
    twoDatabases();
    state.retireContainer(new NotionWork.Database(DATABASE));

    // then
    assertThat(state.dataSources()).containsExactly(OTHER_SOURCE);
  }

  @Test
  @DisplayName("DB 퇴역은 전체가 읽는 자신의 data source도 보존한다")
  void retiringDatabasePreservesFullOwnedChildren() {
    twoDatabases();
    state.beginFullRows(DATA_SOURCE);
    state.retireContainer(new NotionWork.Database(DATABASE));

    // then
    assertThat(state.dataSources()).containsExactly(DATA_SOURCE, OTHER_SOURCE);
  }

  @Test
  @DisplayName("data source 퇴역은 같은 ID의 database를 보존한다")
  void retiringDataSourcePreservesSameNamedDatabase() {
    state.rememberDatabase(DATA_SOURCE, START);
    state.rememberDataSource(DATA_SOURCE, START);
    state.retireContainer(new NotionWork.DataSource(DATA_SOURCE));

    // then
    assertThat(state.databases()).containsExactly(DATA_SOURCE);
  }

  @Test
  @DisplayName("페이지 작업 퇴역은 같은 ID의 컨테이너를 지우지 않는다")
  void retiringPageCannotRemoveContainer() {
    state.rememberDataSource(DATA_SOURCE, START);
    state.retireContainer(new NotionWork.Page(DATA_SOURCE));

    // then
    assertThat(state.dataSources()).containsExactly(DATA_SOURCE);
  }

  @Test
  @DisplayName("생성 시각 없는 cap 응답은 다음 창으로 전진할 수 없다")
  void cappedRowsWithoutCreationBoundaryCannotAdvance() {
    pendingRows(DATA_SOURCE);

    // when·then
    assertThatThrownBy(() -> state.nextRowsWindow(DATA_SOURCE, INCREMENTAL))
        .isInstanceOf(NotionProtocolException.class)
        .hasMessageContaining("window cannot advance");
  }

  @Test
  @DisplayName("이미 읽은 생성 시각보다 과거인 다음 창은 거부한다")
  void backwardsCreationBoundaryCannotAdvance() {
    pendingRows(DATA_SOURCE);
    state.rowsPage(
        DATA_SOURCE, INCREMENTAL, Optional.empty(), Optional.of(SEARCH_FINISHED), true, List.of());
    state.nextRowsWindow(DATA_SOURCE, INCREMENTAL);
    state.rowsPage(DATA_SOURCE, INCREMENTAL, Optional.empty(), Optional.of(START), true, List.of());

    // when·then
    assertThatThrownBy(() -> state.nextRowsWindow(DATA_SOURCE, INCREMENTAL))
        .isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("재기동한 행 창은 마지막 생성 시각을 다음 창의 시작으로 사용한다")
  void rowsCreationBoundarySurvivesRestart() {
    pendingRows(DATA_SOURCE);
    state.rowsPage(DATA_SOURCE, INCREMENTAL, Optional.empty(), Optional.of(START), true, List.of());
    state.nextRowsWindow(DATA_SOURCE, INCREMENTAL);
    state.rowsPage(
        DATA_SOURCE,
        INCREMENTAL,
        Optional.of("cursor"),
        Optional.of(SEARCH_FINISHED),
        true,
        List.of());
    var resumed = resume();

    // then
    assertThat(resumed.rows(DATA_SOURCE, INCREMENTAL).createdFrom()).contains(START);
    assertThat(resumed.rows(DATA_SOURCE, INCREMENTAL).lastCreated()).contains(SEARCH_FINISHED);
  }

  @Test
  @DisplayName("이미 창을 전진한 뒤에도 더 늦은 생성 시각으로 다음 창을 연다")
  void creationWindowCanAdvanceMoreThanOnce() {
    pendingRows(DATA_SOURCE);
    state.rowsPage(DATA_SOURCE, INCREMENTAL, Optional.empty(), Optional.of(START), true, List.of());
    state.nextRowsWindow(DATA_SOURCE, INCREMENTAL);
    state.rowsPage(
        DATA_SOURCE, INCREMENTAL, Optional.empty(), Optional.of(SEARCH_FINISHED), true, List.of());
    state.nextRowsWindow(DATA_SOURCE, INCREMENTAL);
    // then
    assertThat(state.rows(DATA_SOURCE, INCREMENTAL).createdFrom()).contains(SEARCH_FINISHED);
  }

  @Test
  @DisplayName("재기동한 행 확인점은 본문 비교의 성공한 증분 탐색 증거로 남는다")
  void resumedRowWatermarkRemainsSuccessfulExploration() {
    pendingRows(DATA_SOURCE);
    state.completeRows(DATA_SOURCE, INCREMENTAL, SEARCH_FINISHED);
    var row = NotionScopeScenario.page("row", "data_source_id", DATA_SOURCE);
    var context = resume().reconciliation(row, NotionPageRefresh.Path.FULL);
    // then
    assertThat(context.successfulExploration()).contains(START);
  }

  @Test
  @DisplayName("DB 퇴역은 같은 ID를 사용하는 별개 data source를 보존한다")
  void retiringDatabasePreservesSameNamedDataSource() {
    state.rememberDatabase(DATABASE, START);
    state.rememberDataSource(DATABASE, START);
    state.retireContainer(new NotionWork.Database(DATABASE));
    // then
    assertThat(state.dataSources()).containsExactly(DATABASE);
  }

  @Test
  @DisplayName("첫 요청 전의 현대 행 scan은 재기동 시 시작 시각을 덮어쓰지 않는다")
  void unrequestedModernRowsPreserveStartAcrossRestart() {
    pendingRows(DATA_SOURCE);
    var resumed = resume();
    // then
    assertThat(resumed.rows(DATA_SOURCE, INCREMENTAL).startedAt()).isEqualTo(START);
  }

  @Test
  @DisplayName("SNAPSHOT은 진행 중 레거시 v3 순회도 새 전체 범위로 시작한다")
  void snapshotRestartsLegacyPartialTraversal() {
    var traversal = state.fullTraversal().orElseThrow();
    var partial = traversal.completeWork(Optional.empty(), List.of(FIRST)).checkpoint();
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionCheckpointScenario.ROOTS,
            CollectionMode.SNAPSHOT,
            Optional.of(partial),
            SEARCH_FINISHED);
    // then
    assertThat(resumed.coverage()).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
  }

  private void scheduleNextIncremental() {
    state.beginIncremental(START);
    state.incrementalSucceeded(START);
  }

  private void pendingRows(String id) {
    state.rememberDataSource(id, START);
    state.beginRows(id, INCREMENTAL, START);
  }

  private void twoDatabases() {
    state.synchronizeDatabase(DATABASE, List.of(DATA_SOURCE), START);
    state.synchronizeDatabase(OTHER_DATABASE, List.of(OTHER_SOURCE), START);
  }

  private void retryTwoCandidates() {
    state.beginSearch(SearchPhase.ACTIVE, START);
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of(FIRST, SECOND));
    state.retrySearchWork(SearchPhase.ACTIVE, FIRST);
    state.retrySearchWork(SearchPhase.ACTIVE, SECOND);
    state.completeSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);
    state.beginSearch(SearchPhase.ACTIVE, SEARCH_FINISHED);
  }

  private NotionReconciliationState resume() {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        NotionCheckpointScenario.ROOTS,
        CollectionMode.INCREMENTAL,
        Optional.of(state.checkpoint()),
        SEARCH_FINISHED);
  }
}
