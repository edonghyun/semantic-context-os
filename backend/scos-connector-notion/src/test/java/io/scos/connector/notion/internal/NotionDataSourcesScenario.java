package io.scos.connector.notion.internal;

import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.FakeHttpServer.Response;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 행 조회와 실제 본문 완료 저장을 가짜 HTTP에서 실행한다. */
final class NotionDataSourcesScenario implements AutoCloseable {
  static final int INVENTORY_LIMIT = 100;
  static final String DATABASE = "inventory-db";
  static final String DATA_SOURCE = "inventory-ds";
  static final String OLD_ROW = "old-row";
  static final String OVERLAP_ROW = "overlap-row";
  static final String OLD_VERSION = "2020-01-01T00:00:00Z";
  final NotionRefreshScenario data;
  NotionReconciliationState state;
  NotionDataSources collector;
  NotionScope scope;

  NotionDataSourcesScenario() {
    this(new NotionRefreshScenario());
  }

  NotionDataSourcesScenario(NotionRefreshScenario data) {
    this.data = data;
    this.state = data.state;
    var database =
        NotionScopeScenario.object("database", DATABASE, "page_id", NotionRefreshScenario.PAGE);
    database.putArray("data_sources").addObject().put("id", DATA_SOURCE).put("name", "inventory");
    metadata("/v1/databases/" + DATABASE, database);
    metadata(
        "/v1/data_sources/" + DATA_SOURCE,
        NotionScopeScenario.object("data_source", DATA_SOURCE, "database_id", DATABASE));
  }

  void metadata(String path, JsonNode value) {
    data.server.respond(
        "GET", path, new Response(HttpURLConnection.HTTP_OK, value.toString(), Map.of()));
  }

  ObjectNode row(String id, String created) {
    var row =
        NotionScopeScenario.page(id, "data_source_id", DATA_SOURCE)
            .put("created_time", created)
            .put("last_edited_time", OLD_VERSION);
    metadata("/v1/pages/" + id, row);
    data.server.respond(
        "GET",
        "/v1/blocks/" + id + "/children",
        new Response(
            HttpURLConnection.HTTP_OK,
            "{\"object\":\"list\",\"type\":\"block\",\"results\":[],\"has_more\":false,\"next_cursor\":null}",
            Map.of()));
    return row;
  }

  void knownDataSourceWithoutWatermark() {
    oldRowInNewDataSource();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
  }

  void pendingDataSourceWasTrashed() {
    pendingRowBody();
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, data.clock.instant());
    metadata(
        "/v1/data_sources/" + DATA_SOURCE,
        NotionScopeScenario.object("data_source", DATA_SOURCE, "database_id", DATABASE)
            .put("in_trash", true));
  }

  void pendingDataSourceMovedOutside() {
    pendingRowBody();
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.FULL, data.clock.instant());
    metadata(
        "/v1/data_sources/" + DATA_SOURCE,
        NotionScopeScenario.object("data_source", DATA_SOURCE, "workspace", ""));
  }

  List<NotionPageRefresh.Path> pendingRowPaths() {
    return rowPaths(state.checkpoint());
  }

  List<NotionPageRefresh.Path> committedRowPaths() {
    return rowPaths(
        data.stores.checkpoints().read(NotionAssembly.SOURCE).checkpoint().orElseThrow());
  }

  private List<NotionPageRefresh.Path> rowPaths(Checkpoint checkpoint) {
    var containers =
        NotionFixtures.JSON.readTree(checkpoint.valueJson()).at("/incremental/containers");
    var paths = new ArrayList<NotionPageRefresh.Path>();
    for (var container : containers) {
      if (container.has("scan")) paths.add(NotionPageRefresh.Path.INCREMENTAL);
      if (container.has("fullScan")) paths.add(NotionPageRefresh.Path.FULL);
    }
    return paths;
  }

  void rejectedQueryBeforeAccessibleSource() {
    addedDataSourceToDatabase();
    data.server.respond(
        "POST",
        "/v1/data_sources/" + DATA_SOURCE + "/query",
        NotionClientScenario.objectNotFound());
  }

  void dataSourceWasRemovedFromDatabase() {
    completedDataSourceWasRestarted();
    var database =
        NotionScopeScenario.object("database", DATABASE, "page_id", NotionRefreshScenario.PAGE);
    database.putArray("data_sources");
    metadata("/v1/databases/" + DATABASE, database);
  }

  void knownDatabaseWasDeleted() {
    completedDataSourceWasRestarted();
    data.server.respond("GET", "/v1/databases/" + DATABASE, NotionClientScenario.objectNotFound());
  }

  void knownDataSourceWasDeleted() {
    rejectedDataSourceBeforeAccessibleSource();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
  }

  boolean deletedDataSourceIsInInventory() {
    return state.dataSources().contains(DATA_SOURCE);
  }

  void rejectedDataSourceBeforeAccessibleSource() {
    addedDataSourceToDatabase();
    data.server.respond(
        "GET", "/v1/data_sources/" + DATA_SOURCE, NotionClientScenario.objectNotFound());
  }

  void rejectedRowBeforeAccessibleRow() {
    rows(row("rejected-row", OLD_VERSION), row(OLD_ROW, OLD_VERSION));
    data.server.respond(
        "GET", "/v1/blocks/rejected-row/children", NotionClientScenario.objectNotFound());
  }

  void overlapDiscoversNewRowAfterBackfill() {
    oldRowInNewDataSource();
    var edited =
        row(OVERLAP_ROW, OLD_VERSION)
            .put("last_edited_time", NotionIncrementalScenario.START.toString());
    metadata("/v1/pages/" + OVERLAP_ROW, edited);
    var request = NotionFixtures.JSON.createObjectNode().put("page_size", INVENTORY_LIMIT);
    NotionRowQuery.editedSince(
            NotionIncrementalScenario.START.minus(NotionRefreshScenario.ROOTS.overlap()))
        .applyTo(request);
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    response.putArray("results").add(edited);
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "POST")
            .put("path", "/v1/data_sources/" + DATA_SOURCE + "/query");
    fault.set("body", request);
    fault
        .putArray("responses")
        .addObject()
        .put("status", HttpURLConnection.HTTP_OK)
        .put("text", response.toString())
        .putObject("headers");
    data.server.fault(fault);
  }

  void overlapFindsRejectedRowBody() {
    overlapDiscoversNewRowAfterBackfill();
    data.server.respond(
        "GET", "/v1/blocks/" + OVERLAP_ROW + "/children", NotionClientScenario.objectNotFound());
  }

  void oldRowInNewDataSource() {
    rows(row(OLD_ROW, OLD_VERSION));
  }

  void rows(JsonNode... rows) {
    var result =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    var results = result.putArray("results");
    for (JsonNode row : rows) results.add(row);
    data.server.respond(
        "POST",
        "/v1/data_sources/" + DATA_SOURCE + "/query",
        new Response(HttpURLConnection.HTTP_OK, result.toString(), Map.of()));
  }

  Instant restartedScanStart() {
    return NotionIncrementalScenario.START.plus(Duration.ofMinutes(1));
  }

  void expiredRowsCursor() {
    oldRowInNewDataSource();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.clock.advance(Duration.ofMinutes(1));
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.of("expired-rows"),
        Optional.of(Instant.parse(OLD_VERSION)),
        false,
        List.of());
    var request =
        NotionFixtures.JSON
            .createObjectNode()
            .put("page_size", INVENTORY_LIMIT)
            .put("start_cursor", "expired-rows");
    NotionRowQuery.editedSince(
            NotionIncrementalScenario.START.minus(NotionRefreshScenario.ROOTS.overlap()))
        .applyTo(request);
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "POST")
            .put("path", "/v1/data_sources/" + DATA_SOURCE + "/query");
    fault.set("body", request);
    fault
        .putArray("responses")
        .addObject()
        .put("status", HttpURLConnection.HTTP_BAD_REQUEST)
        .put(
            "text",
            "{\"code\":\"validation_error\",\"message\":\"The start_cursor provided is invalid: expired-rows\"}")
        .putObject("headers");
    data.server.fault(fault);
  }

  void nestedWikiDataSource() {
    var nested =
        NotionScopeScenario.object("data_source", "wiki-nested", "data_source_id", DATA_SOURCE)
            .put("created_time", OLD_VERSION);
    metadata("/v1/data_sources/wiki-nested", nested);
    rows(nested);
    data.server.respond(
        "POST",
        "/v1/data_sources/wiki-nested/query",
        new Response(HttpURLConnection.HTTP_OK, NotionApiClientTest.EMPTY_SEARCH, Map.of()));
  }

  Optional<Instant> nestedWatermark() {
    return state.rowWatermark("wiki-nested");
  }

  void secondDataSourceFails() {
    oldRowInNewDataSource();
    addedDataSourceToDatabase();
    data.server.respond(
        "GET",
        "/v1/data_sources/second-ds",
        new Response(
            HttpURLConnection.HTTP_UNAUTHORIZED,
            "{\"object\":\"error\",\"code\":\"unauthorized\"}",
            Map.of()));
  }

  void cyclicRowsCursor() {
    data.server.cycleCursor(
        "POST", "/v1/data_sources/" + DATA_SOURCE + "/query", "page_or_data_source");
  }

  void unreadableDataSourceParent() {
    metadata(
        "/v1/data_sources/" + DATA_SOURCE,
        NotionScopeScenario.object("data_source", DATA_SOURCE, "page_id", "unreadable"));
    data.server.respond("GET", "/v1/pages/unreadable", NotionClientScenario.objectNotFound());
  }

  void unorderedRows() {
    rows(row("newer-created", "2022-01-01T00:00:00Z"), row("older-created", OLD_VERSION));
  }

  void dataSourceScopeBecomesKnown() {
    unreadableDataSourceParent();
    collect();
    metadata(
        "/v1/data_sources/" + DATA_SOURCE,
        NotionScopeScenario.object("data_source", DATA_SOURCE, "database_id", DATABASE));
    oldRowInNewDataSource();
    collector = null;
  }

  void newVersionAfterRowBodyRejection() {
    oldRowInNewDataSource();
    data.server.respond(
        "GET", "/v1/blocks/" + OLD_ROW + "/children", NotionClientScenario.objectNotFound());
    collect();
    var edited =
        row(OLD_ROW, OLD_VERSION)
            .put("last_edited_time", NotionIncrementalScenario.NEXT_EXPLORATION.toString());
    metadata("/v1/pages/" + OLD_ROW, edited);
    rows(edited);
    collector = null;
  }

  void newRowEditAppearsAfterRejectedSlice() {
    rejectedRowScanWasResumed();
    collect();
    var edited =
        row(OLD_ROW, OLD_VERSION)
            .put("last_edited_time", NotionIncrementalScenario.NEXT_EXPLORATION.toString());
    metadata("/v1/pages/" + OLD_ROW, edited);
    collector = null;
  }

  Optional<String> completedRowVersion() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(data.fingerprint(), List.of(OLD_ROW), data.input.cancel())
                .get(OLD_ROW))
        .flatMap(summary -> summary.completion().map(value -> value.version()));
  }

  void legacyTerminalRowScanWasResumed() {
    oldRowInNewDataSource();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(NotionIncrementalScenario.START),
        false,
        List.of(new NotionWork.Page("rejected-row")));
    data.server.respond("GET", "/v1/pages/rejected-row", NotionClientScenario.objectNotFound());
    var legacy = NotionFixtures.JSON.readTree(state.checkpoint().valueJson());
    ((ObjectNode) legacy.at("/incremental/containers/0/scan")).remove("listingComplete");
    state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(new Checkpoint(4, legacy.toString())),
            data.clock.instant());
  }

  void rejectedRowScanWasResumed() {
    oldRowInNewDataSource();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(Instant.parse(OLD_VERSION)),
        false,
        List.of(new NotionWork.Page("rejected-row")));
    data.server.respond("GET", "/v1/pages/rejected-row", NotionClientScenario.objectNotFound());
    state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            data.clock.instant());
  }

  void pendingRowBody() {
    row(OLD_ROW, OLD_VERSION);
    rows();
    state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(Instant.parse(OLD_VERSION)),
        false,
        List.of(new NotionWork.Page(OLD_ROW)));
  }

  void completedDataSourceWasRestarted() {
    oldRowInNewDataSource();
    collect();
    data.clock.advance(Duration.ofMinutes(1));
    collector = null;
    state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            data.clock.instant());
  }

  JsonNode lastQuery() {
    return data.server.requests().stream()
        .filter(r -> r.path().endsWith("/query"))
        .map(r -> NotionFixtures.JSON.readTree(r.body()))
        .toList()
        .getLast();
  }

  Optional<Instant> dataSourceWatermark() {
    return state.rowWatermark(DATA_SOURCE);
  }

  void unadvancingCreationWindow() {
    var same = row("same-minute", OLD_VERSION);
    window(Optional.empty(), true, same);
    window(Optional.of(OLD_VERSION), true, same);
  }

  void cappedAcrossWindows() {
    rows();
    var first = row("first-row", "2020-01-01T00:00:00Z");
    var boundary = row("boundary-row", "2021-01-01T00:00:00Z");
    var last = row("last-row", "2022-01-01T00:00:00Z");
    window(Optional.empty(), true, first, boundary);
    window(Optional.of("2021-01-01T00:00:00Z"), false, boundary, last);
  }

  void window(Optional<String> from, boolean capped, JsonNode... rows) {
    var body = NotionFixtures.JSON.createObjectNode().put("page_size", 100);
    body.putArray("sorts")
        .addObject()
        .put("timestamp", "created_time")
        .put("direction", "ascending");
    from.ifPresent(
        start ->
            body.putObject("filter")
                .put("timestamp", "created_time")
                .putObject("created_time")
                .put("on_or_after", start));
    var result =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    var results = result.putArray("results");
    for (JsonNode row : rows) results.add(row);
    if (capped)
      result
          .putObject("request_status")
          .put("type", "incomplete")
          .put("incomplete_reason", "query_result_limit_reached");
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "POST")
            .put("path", "/v1/data_sources/" + DATA_SOURCE + "/query");
    fault.set("body", body);
    fault
        .putArray("responses")
        .addObject()
        .put("status", HttpURLConnection.HTTP_OK)
        .put("text", result.toString())
        .putObject("headers");
    data.server.fault(fault);
  }

  Set<String> collectedRows() {
    return data
        .raw
        .listActive(NotionAssembly.SOURCE, INVENTORY_LIMIT, Optional.empty())
        .items()
        .stream()
        .filter(r -> r.resourceType().equals("notion.page"))
        .map(r -> r.externalId())
        .collect(Collectors.toSet());
  }

  void addedDataSourceToDatabase() {
    var database =
        NotionScopeScenario.object("database", DATABASE, "page_id", NotionRefreshScenario.PAGE);
    var sources = database.putArray("data_sources");
    sources.addObject().put("id", DATA_SOURCE).put("name", "existing");
    sources.addObject().put("id", "second-ds").put("name", "new");
    metadata("/v1/databases/" + DATABASE, database);
    metadata(
        "/v1/data_sources/second-ds",
        NotionScopeScenario.object("data_source", "second-ds", "database_id", DATABASE));
    var page = row("second-old-row", OLD_VERSION);
    page.withObject("/parent").put("data_source_id", "second-ds");
    metadata("/v1/pages/second-old-row", page);
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    response.putArray("results").add(page);
    data.server.respond(
        "POST",
        "/v1/data_sources/second-ds/query",
        new Response(HttpURLConnection.HTTP_OK, response.toString(), Map.of()));
  }

  boolean poll() {
    prepareCollector();
    return collector.poll(scope);
  }

  boolean collect() {
    prepareCollector();
    return collector.collect(
        new NotionWork.Database(DATABASE), NotionPageRefresh.Path.INCREMENTAL, scope);
  }

  void collectFull() {
    prepareCollector();
    collector.collect(new NotionWork.Database(DATABASE), NotionPageRefresh.Path.FULL, scope);
  }

  boolean collectWork(NotionWork work, NotionPageRefresh.Path path) {
    prepareCollector();
    return collector.collect(work, path, scope);
  }

  boolean collectCandidate(NotionWork work) {
    prepareCollector();
    return collector.collectCandidate(work, scope);
  }

  void restartCollector() {
    collector = null;
  }

  void databaseMovedOutside() {
    metadata(
        "/v1/databases/" + DATABASE,
        NotionScopeScenario.object("database", DATABASE, "workspace", ""));
  }

  void databaseParentIsUnknown() {
    var database = NotionScopeScenario.object("database", DATABASE, "page_id", "unreadable");
    database.putArray("data_sources");
    metadata("/v1/databases/" + DATABASE, database);
    data.server.respond("GET", "/v1/pages/unreadable", NotionClientScenario.objectNotFound());
  }

  void rejectedDatabase() {
    data.server.respond("GET", "/v1/databases/" + DATABASE, NotionClientScenario.objectNotFound());
  }

  long metadataRequests(String id) {
    return data.server.requests().stream().filter(r -> r.path().endsWith("/" + id)).count();
  }

  private void prepareCollector() {
    if (collector != null) return;
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(data.input, data.clock);
    var mutations = new NotionMutations(data.input.content(), data.clock);
    var refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client, calls, mutations, data.input, state, data::commit, data.server.token()));
    collector =
        new NotionDataSources(
            new NotionDataSources.Dependencies(
                client,
                calls,
                mutations,
                data.input,
                state,
                data::commit,
                data.server.token(),
                data.clock,
                refresh));
    scope = new NotionScope(NotionRefreshScenario.ROOTS, client, calls, data.server.token());
  }

  boolean completedOldRow() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(data.fingerprint(), List.of(OLD_ROW), data.input.cancel())
                .get(OLD_ROW))
        .map(summary -> summary.isCompleted(OLD_VERSION))
        .orElse(false);
  }

  long bodyRequests(String id) {
    return data.server.requests().stream()
        .filter(r -> r.path().equals("/v1/blocks/" + id + "/children"))
        .count();
  }

  long pageRequests(String id) {
    return data.server.requests().stream().filter(r -> r.path().equals("/v1/pages/" + id)).count();
  }

  JsonNode firstQuery() {
    return data.server.requests().stream()
        .filter(r -> r.path().endsWith("/query"))
        .map(r -> NotionFixtures.JSON.readTree(r.body()))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public void close() {
    data.close();
  }
}
