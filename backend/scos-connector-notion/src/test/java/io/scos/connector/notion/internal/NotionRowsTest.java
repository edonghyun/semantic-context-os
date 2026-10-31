package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("대량 행 탐색은 공급자의 query 한도를 넘어 발견한다")
class NotionRowsTest {
  // 최초 목록과 세 번의 재시작마다 목록·만료 응답을 한 번씩 읽는다.
  static final int REQUESTS_THROUGH_FOURTH_EXPIRY = 8;
  final NotionRowsScenario context = new NotionRowsScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("만 건 제한 뒤 경계를 포함해 만 한 건의 ID를 모두 얻는다")
  void discoversEveryIdPastTenThousandLimit() {
    // given
    context.largeCappedDataSource();
    // when
    context.collect();
    // then
    assertThat(context.discovered).isEqualTo(context.expected);
  }

  @Test
  @DisplayName("같은 data source의 전체와 증분은 서로의 행 대기열을 덮어쓰지 않는다")
  void incrementalHasIndependentScanDuringFull() {
    // given
    context.separateRowsPerPath();
    // when
    context.collectFullWithIncremental();
    // then
    assertThat(context.fullRows).containsExactly("full-old");
    assertThat(context.incrementalRows).containsExactly("incremental-new");
  }

  @Test
  @DisplayName("빈 행 목록도 탐색 완료를 반환한다")
  void emptyRowsReturnCompletion() {
    context.emptyDataSource();
    var completed = context.collect();

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("거부된 행 목록은 미완료를 반환한다")
  void rejectedRowsReturnIncomplete() {
    context.rejectedDataSource();
    var completed = context.collect();

    // then
    assertThat(completed).isFalse();
  }

  @Test
  @DisplayName("행 목록 거부는 해당 data source의 증분 재시도를 보류한다")
  void rejectedRowsDeferDataSource() {
    context.rejectedDataSource();
    context.collect();

    // then
    assertThat(context.deferred()).isTrue();
  }

  @Test
  @DisplayName("반복되는 커서 만료는 세 번 재시도 뒤 전파한다")
  void expiredCursorStopsAfterThreeRestarts() {
    context.repeatedlyExpiredCursor();

    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(NotionCursorExpired.class);
    assertThat(context.queryRequests()).isEqualTo(REQUESTS_THROUGH_FOURTH_EXPIRY);
  }

  @Test
  @DisplayName("행 조회 거부는 run의 항목 거부로 기록한다")
  void rejectedRowsAreReportedToRun() {
    context.rejectedDataSource();
    context.collect();
    // then
    assertThat(context.rejectionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("첫 행 조회가 실패해도 탐색 시작 시각을 재개 확인점에 보존한다")
  void failingQueryPreservesScanStart() {
    context.queryFailsBeforeFirstResponse();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedScanField("startedAt"))
        .contains(NotionIncrementalScenario.START.toString());
  }

  @Test
  @DisplayName("커서 재시작 뒤 조회가 실패해도 만료 커서를 확인점에 남기지 않는다")
  void failingRestartedQueryCannotRestoreExpiredCursor() {
    context.queryFailsAfterCursorRestart();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedScanField("cursor")).isEmpty();
  }

  @Test
  @DisplayName("다음 생성 창 조회가 실패해도 전진한 창 경계를 확인점에 보존한다")
  void failingNextWindowPreservesCreationBoundary() {
    context.queryFailsAfterCreationWindowAdvance();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedScanField("createdFrom")).contains(NotionRowsScenario.BOUNDARY);
  }

  @Test
  @DisplayName("마지막 행의 수집이 성공하면 탐색 완료를 반환한다")
  void collectedLastRowReturnsCompletion() {
    context.singleRowDataSource();
    var completed = context.collect();
    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("다음 행 목록 조회가 실패해도 이전 응답의 대기 행은 먼저 수집한다")
  void pendingRowIsCollectedBeforeFailingNextQuery() {
    context.pendingRowPrecedesFailingQuery();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.discovered).containsExactly("pending-row");
  }

  @Test
  @DisplayName("재개한 cap 마지막 응답은 다음 생성 창으로 전진해 행을 수집한다")
  void resumedCappedWindowCollectsNextWindow() {
    context.cappedTerminalWindowWasResumed();
    context.collect();
    // then
    assertThat(context.discovered).containsExactly(NotionRowsScenario.RESUMED_WINDOW_ROW);
  }

  @Test
  @DisplayName("목록이 끝난 행의 남은 본문을 수집하면 탐색 완료를 반환한다")
  void terminalRowCompletionReturnsTrue() {
    context.terminalRowWasCollected();
    var completed = context.collect();
    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("재개한 cap의 다음 조회가 실패해도 전진한 창 경계를 저장한다")
  void resumedCapPersistsBoundaryBeforeFailingQuery() {
    context.resumedCapQueryFails();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.persistedScanField("createdFrom")).contains(NotionRowsScenario.BOUNDARY);
  }
}
