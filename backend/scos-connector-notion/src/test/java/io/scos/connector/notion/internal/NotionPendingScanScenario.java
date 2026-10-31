package io.scos.connector.notion.internal;

import io.scos.spi.connector.CollectionMode;
import java.util.List;
import java.util.Optional;

/** 거부된 작업이 남은 검색·행 탐색의 전환 준비와 확인점 연결. */
final class NotionPendingScanScenario {
  enum Transition {
    SEARCH_CURSOR_EXPIRED,
    ROW_CURSOR_EXPIRED,
    ROW_WINDOW
  }

  static final String DATA_SOURCE = "pending-ds";
  static final NotionWork.Page REJECTED = new NotionWork.Page("rejected");
  private final NotionReconciliationState state =
      NotionReconciliationState.read(
          NotionFixtures.JSON,
          NotionReconciliationStateTest.ROOTS,
          CollectionMode.INCREMENTAL,
          Optional.empty(),
          NotionReconciliationStateTest.START);

  NotionPendingScanScenario() {
    state.beginSearch(SearchPhase.ACTIVE, NotionReconciliationStateTest.START);
    state.searchPage(SearchPhase.ACTIVE, Optional.of("next"), List.of(REJECTED));
    state.rememberDataSource(DATA_SOURCE, NotionReconciliationStateTest.START);
    state.beginRows(
        DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, NotionReconciliationStateTest.START);
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(NotionReconciliationStateTest.START),
        true,
        List.of(REJECTED));
  }

  void nextRowResponse() {
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(NotionReconciliationStateTest.START),
        false,
        List.of());
  }

  void nextSearchResponse() {
    state.searchPage(SearchPhase.ACTIVE, Optional.empty(), List.of());
  }

  void transition(Transition transition) {
    switch (transition) {
      case SEARCH_CURSOR_EXPIRED -> state.restartSearch(SearchPhase.ACTIVE);
      case ROW_CURSOR_EXPIRED -> state.restartRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL);
      case ROW_WINDOW -> state.nextRowsWindow(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL);
    }
  }

  List<NotionWork> pendingSearch() {
    return resumed().beginSearch(SearchPhase.ACTIVE, NotionReconciliationStateTest.START).work();
  }

  List<NotionWork> pendingRows() {
    return resumed()
        .beginRows(
            DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, NotionReconciliationStateTest.START)
        .work();
  }

  List<NotionWork> pending(Transition transition) {
    return transition == Transition.SEARCH_CURSOR_EXPIRED ? pendingSearch() : pendingRows();
  }

  private NotionReconciliationState resumed() {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        NotionReconciliationStateTest.ROOTS,
        CollectionMode.INCREMENTAL,
        Optional.of(state.checkpoint()),
        NotionReconciliationStateTest.START);
  }
}
