package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static io.scos.connector.notion.internal.NotionFixtures.DATA_SOURCE;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/** Search·행 창·본문 인계가 함께 진행 중인 확인점을 만든다. */
final class NotionCheckpointScenario {
  static final Instant START = Instant.parse("2026-10-25T00:00:00Z");
  static final Instant SEARCH_FINISHED = Instant.parse("2026-10-25T00:00:40Z");
  static final NotionConfiguration.Roots ROOTS = NotionFixtures.roots("root");
  static final String LATER_BODY_VERSION = "2026-10-25T00:01:00Z";

  static NotionReconciliationState fresh() {
    return NotionReconciliationState.read(
        NotionFixtures.JSON, ROOTS, CollectionMode.INCREMENTAL, Optional.empty(), START);
  }

  private final ObjectNode saved;

  NotionCheckpointScenario() {
    var state = fresh();
    var start = START;
    var path = NotionPageRefresh.Path.INCREMENTAL;
    state.beginSearch(SearchPhase.ACTIVE, start);
    state.searchPage(SearchPhase.ACTIVE, Optional.of("search-cursor"), List.of());
    state.rememberDataSource(DATA_SOURCE, start);
    state.beginRows(DATA_SOURCE, path, start);
    state.completeRows(DATA_SOURCE, path, start);
    state.beginRows(DATA_SOURCE, path, start);
    state.rowsPage(DATA_SOURCE, path, Optional.empty(), Optional.of(start), true, List.of());
    state.nextRowsWindow(DATA_SOURCE, path);
    state.rowsPage(
        DATA_SOURCE,
        path,
        Optional.of("row-cursor"),
        Optional.of(SEARCH_FINISHED),
        true,
        List.of(new NotionWork.Page(CHILD_PAGE)));
    state.bodyFinished("root", start.toString(), List.of(new NotionWork.Page(CHILD_PAGE)));
    state.recordBodyProgress(
        "root",
        LATER_BODY_VERSION,
        List.of(new NotionWork.BlockChildren("root", Optional.empty())));
    saved = (ObjectNode) NotionFixtures.JSON.readTree(state.checkpoint().valueJson());
  }

  void replace(String pointer, String json) {
    int separator = pointer.lastIndexOf('/');
    var parent = (ObjectNode) saved.at(pointer.substring(0, separator));
    parent.set(pointer.substring(separator + 1), NotionFixtures.JSON.readTree(json));
  }

  void remove(String pointer) {
    int separator = pointer.lastIndexOf('/');
    var parent = (ObjectNode) saved.at(pointer.substring(0, separator));
    parent.remove(pointer.substring(separator + 1));
  }

  NotionReconciliationState resume() {
    return NotionReconciliationState.read(
        NotionFixtures.JSON,
        ROOTS,
        CollectionMode.INCREMENTAL,
        Optional.of(new Checkpoint(NotionReconciliationState.SCHEMA_VERSION, saved.toString())),
        SEARCH_FINISHED);
  }
}
