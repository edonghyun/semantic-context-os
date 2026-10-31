package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionReconciliationState.RowScan;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/** 행 생성 시각 창·커서·미완료 작업을 한 탐색으로 진행한다. */
final class NotionRows {
  record Dependencies(
      NotionReads reads, NotionReconciliationState state, PageSink sink, Clock clock) {}

  // 커서 만료가 반복되면 미확정 오류로 남기고 무한 탐색을 막는다.
  private static final int MAX_CURSOR_RESTARTS = 3;
  private final Dependencies deps;

  NotionRows(Dependencies deps) {
    this.deps = deps;
  }

  boolean collect(String id, NotionPageRefresh.Path path, Predicate<NotionWork> collect) {
    var scan = deps.state().beginRows(id, path, deps.clock().instant());
    checkpoint();
    finishWork(id, path, collect);
    if (scan.listingComplete()) {
      if (!deps.state().rows(id, path).work().isEmpty()) {
        deps.state().reopenRows(id, path, deps.clock().instant());
      } else if (scan.cursor().isEmpty()) {
        if (!scan.limited()) return completeRows(id, path);
        deps.state().nextRowsWindow(id, path);
        checkpoint();
      }
    }
    int restarts = 0;
    while (true) {
      scan = deps.state().rows(id, path);
      NotionApiClient.RowResult result;
      try {
        var found = readRows(id, path, scan);
        if (found.isEmpty()) return false;
        result = found.get();
      } catch (NotionCursorExpired expired) {
        if (++restarts > MAX_CURSOR_RESTARTS) throw expired;
        deps.state().restartRows(id, path);
        checkpoint();
        continue;
      }
      recordRows(id, path, scan, result);
      checkpoint();
      finishWork(id, path, collect);
      scan = deps.state().rows(id, path);
      if (scan.cursor().isPresent()) continue;
      if (scan.limited()) {
        deps.state().nextRowsWindow(id, path);
        checkpoint();
        continue;
      }
      return completeRows(id, path);
    }
  }

  private boolean completeRows(String id, NotionPageRefresh.Path path) {
    var scan = deps.state().rows(id, path);
    if (!scan.work().isEmpty()) return false;
    deps.state().completeRows(id, path, deps.clock().instant());
    checkpoint();
    return true;
  }

  private Optional<NotionApiClient.RowResult> readRows(
      String id, NotionPageRefresh.Path path, RowScan scan) {
    var baseQuery =
        scan.lowerBound().map(NotionRowQuery::editedSince).orElseGet(NotionRowQuery::all);
    var query = scan.createdFrom().map(baseQuery::createdOnOrAfter).orElse(baseQuery);
    var requested = scan.cursor();
    return deps.reads()
        .rows(id, requested, query)
        .fold(
            rejected -> {
              deps.sink()
                  .accept(
                      new CollectionPage(List.of(), List.of(rejected), deps.state().checkpoint()));
              deps.state().defer(new NotionWork.DataSource(id), path);
              return Optional.<NotionApiClient.RowResult>empty();
            },
            Optional::of);
  }

  private void recordRows(
      String id, NotionPageRefresh.Path path, RowScan scan, NotionApiClient.RowResult result) {
    Instant previousCreated =
        scan.lastCreated()
            .orElseGet(() -> deps.state().rows(id, path).createdFrom().orElse(Instant.MIN));
    for (JsonNode row : result.page().results()) {
      if (row.path("in_trash").asBoolean())
        throw new NotionProtocolException(NotionRootCollection.TRASHED_ROW);
      Instant created = NotionMutations.timestamp(row.path("created_time").asString());
      if (created.isBefore(previousCreated))
        throw new NotionProtocolException("Notion rows are not ordered by creation time");
      previousCreated = created;
    }
    var jobs =
        result.page().results().stream()
            .map(
                row -> {
                  if (row.path("object").asString().equals(NotionObjects.PAGE)) {
                    deps.state().observePage(row, path);
                    return (NotionWork) new NotionWork.Page(row.path("id").asString());
                  }
                  return new NotionWork.DataSource(row.path("id").asString());
                })
            .toList();
    var lastCreated =
        result.page().results().stream()
            .reduce((first, last) -> last)
            .map(row -> NotionMutations.timestamp(row.path("created_time").asString()));
    deps.state()
        .rowsPage(
            id,
            path,
            result.page().nextCursor(),
            lastCreated,
            result instanceof NotionApiClient.CappedRows,
            jobs);
  }

  private void finishWork(String id, NotionPageRefresh.Path path, Predicate<NotionWork> collect) {
    var scan = deps.state().rows(id, path);
    for (var work : scan.work()) {
      if (collect.test(work)) deps.state().completedRowWork(id, path, work);
      checkpoint();
    }
  }

  private void checkpoint() {
    deps.sink().accept(new CollectionPage(List.of(), List.of(), deps.state().checkpoint()));
  }
}
