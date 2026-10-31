package io.scos.connector.notion.internal;

import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.CollectionPage;
import io.scos.support.FakeHttpServer.Response;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 실 HTTP 페이지와 실제 확인점 커밋으로 대량 ID 발견만 검증한다. */
final class NotionRowsScenario implements AutoCloseable {
  // 실제 query 한도와 한 요청의 최대 행 수. 한도를 한 건 넘긴 fixture다.
  static final int QUERY_LIMIT = 10_000;
  static final int PAGE_SIZE = 100;
  static final String CREATED = "2020-01-01T00:00:00Z";
  static final String BOUNDARY = "2020-01-01T00:01:00Z";
  static final String NEWEST = "2020-01-01T00:02:00Z";
  static final String DATA_SOURCE = "large-ds";
  static final String RESUMED_WINDOW_ROW = "resumed-window-row";
  final NotionRefreshScenario data = new NotionRefreshScenario();
  final Set<String> discovered = new HashSet<>();
  final Set<String> expected = new HashSet<>();
  final Set<String> fullRows = new HashSet<>();
  final Set<String> incrementalRows = new HashSet<>();

  void largeCappedDataSource() {
    for (int offset = 0; offset < QUERY_LIMIT; offset += PAGE_SIZE) {
      var cursor = offset == 0 ? Optional.<String>empty() : Optional.of("large-" + offset);
      boolean capped = offset + PAGE_SIZE == QUERY_LIMIT;
      var next = capped ? Optional.<String>empty() : Optional.of("large-" + (offset + PAGE_SIZE));
      var response = response(next, capped);
      var results = response.putArray("results");
      for (int row = offset; row < offset + PAGE_SIZE; row++)
        results.add(row(row, row == QUERY_LIMIT - 1 ? BOUNDARY : CREATED));
      answer(cursor, Optional.empty(), response);
    }
    var finalWindow = response(Optional.empty(), false);
    finalWindow
        .putArray("results")
        .add(row(QUERY_LIMIT - 1, BOUNDARY))
        .add(row(QUERY_LIMIT, NEWEST));
    answer(Optional.empty(), Optional.of(Instant.parse(BOUNDARY)), finalWindow);
  }

  private ObjectNode row(int number, String created) {
    String id = "large-row-" + number;
    expected.add(id);
    return NotionScopeScenario.page(id, "data_source_id", DATA_SOURCE).put("created_time", created);
  }

  private ObjectNode response(Optional<String> next, boolean capped) {
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", next.isPresent());
    if (next.isPresent()) response.put("next_cursor", next.get());
    else response.putNull("next_cursor");
    if (capped)
      response
          .putObject("request_status")
          .put("type", "incomplete")
          .put("incomplete_reason", "query_result_limit_reached");
    return response;
  }

  private void answer(Optional<String> cursor, Optional<Instant> created, JsonNode response) {
    answerQuery(
        cursor,
        created.map(NotionRowQuery.all()::createdOnOrAfter).orElseGet(NotionRowQuery::all),
        response);
  }

  private void answerQuery(Optional<String> cursor, NotionRowQuery query, JsonNode response) {
    var body = NotionFixtures.JSON.createObjectNode().put("page_size", PAGE_SIZE);
    query.applyTo(body);
    cursor.ifPresent(c -> body.put("start_cursor", c));
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
        .put("text", response.toString())
        .putObject("headers");
    data.server.fault(fault);
  }

  void separateRowsPerPath() {
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.completeRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    var full = response(Optional.empty(), false);
    full.putArray("results")
        .add(
            NotionScopeScenario.page("full-old", "data_source_id", DATA_SOURCE)
                .put("created_time", CREATED));
    answerQuery(Optional.empty(), NotionRowQuery.all(), full);
    var incremental = response(Optional.empty(), false);
    incremental
        .putArray("results")
        .add(
            NotionScopeScenario.page("incremental-new", "data_source_id", DATA_SOURCE)
                .put("created_time", NEWEST));
    answerQuery(
        Optional.empty(),
        NotionRowQuery.editedSince(
            NotionIncrementalScenario.START.minus(NotionRefreshScenario.ROOTS.overlap())),
        incremental);
  }

  void collectFullWithIncremental() {
    var full = reader();
    var incremental = reader();
    full.collect(
        DATA_SOURCE,
        NotionPageRefresh.Path.FULL,
        work -> {
          fullRows.add(((NotionWork.Page) work).pageId());
          data.clock.advance(Duration.ofMinutes(1));
          incremental.collect(
              DATA_SOURCE,
              NotionPageRefresh.Path.INCREMENTAL,
              candidate -> incrementalRows.add(((NotionWork.Page) candidate).pageId()));
          return true;
        });
  }

  private NotionRows reader() {
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(data.input, data.clock);
    var reads =
        new NotionReads(
            client,
            calls,
            new NotionMutations(data.input.content(), data.clock),
            data.server.token());
    return new NotionRows(new NotionRows.Dependencies(reads, data.state, this::commit, data.clock));
  }

  boolean collect() {
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(data.input, data.clock);
    var reads =
        new NotionReads(
            client,
            calls,
            new NotionMutations(data.input.content(), data.clock),
            data.server.token());
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    return new NotionRows(new NotionRows.Dependencies(reads, data.state, this::commit, data.clock))
        .collect(
            DATA_SOURCE,
            NotionPageRefresh.Path.INCREMENTAL,
            work -> discovered.add(((NotionWork.Page) work).pageId()));
  }

  void pendingRowPrecedesFailingQuery() {
    queryFailsBeforeFirstResponse();
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.of("next-fails"),
        Optional.empty(),
        false,
        List.of(new NotionWork.Page("pending-row")));
  }

  void cappedTerminalWindowWasResumed() {
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(Instant.parse(BOUNDARY)),
        true,
        List.of());
    var next = response(Optional.empty(), false);
    next.putArray("results")
        .add(
            NotionScopeScenario.page(RESUMED_WINDOW_ROW, "data_source_id", DATA_SOURCE)
                .put("created_time", NEWEST));
    answer(Optional.empty(), Optional.of(Instant.parse(BOUNDARY)), next);
  }

  void terminalRowWasCollected() {
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(Instant.parse(CREATED)),
        false,
        List.of(new NotionWork.Page("pending-row")));
  }

  void resumedCapQueryFails() {
    queryFailsBeforeFirstResponse();
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.empty(),
        Optional.of(Instant.parse(BOUNDARY)),
        true,
        List.of());
  }

  void singleRowDataSource() {
    var result = response(Optional.empty(), false);
    result.putArray("results").add(row(0, CREATED));
    answer(Optional.empty(), Optional.empty(), result);
  }

  void emptyDataSource() {
    var empty = response(Optional.empty(), false);
    empty.putArray("results");
    answer(Optional.empty(), Optional.empty(), empty);
  }

  void rejectedDataSource() {
    data.server.respond(
        "POST",
        "/v1/data_sources/" + DATA_SOURCE + "/query",
        NotionClientScenario.objectNotFound());
  }

  void repeatedlyExpiredCursor() {
    data.server.respond(
        "POST",
        "/v1/data_sources/" + DATA_SOURCE + "/query",
        new Response(
            HttpURLConnection.HTTP_BAD_REQUEST,
            "{\"code\":\"validation_error\",\"message\":\"The start_cursor provided is invalid: cursor\"}",
            Map.of()));
    var request = NotionFixtures.JSON.createObjectNode().put("page_size", PAGE_SIZE);
    NotionRowQuery.all().applyTo(request);
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "POST")
            .put("path", "/v1/data_sources/" + DATA_SOURCE + "/query");
    fault.set("body", request);
    var responses = fault.putArray("responses");
    var listed = response(Optional.of("expired"), false);
    listed.putArray("results");
    // 네 번째 만료까지 응답하고, 잘못된 무한 재시도는 인증 실패로 즉시 드러낸다.
    final int allowedListings = 4;
    for (int attempt = 0; attempt < allowedListings; attempt++)
      responses
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", listed.toString())
          .putObject("headers");
    responses
        .addObject()
        .put("status", HttpURLConnection.HTTP_UNAUTHORIZED)
        .put("text", "{\"code\":\"unauthorized\"}")
        .putObject("headers");
    data.server.fault(fault);
  }

  void queryFailsBeforeFirstResponse() {
    data.server.respond(
        "POST",
        "/v1/data_sources/" + DATA_SOURCE + "/query",
        new Response(HttpURLConnection.HTTP_UNAUTHORIZED, "{\"code\":\"unauthorized\"}", Map.of()));
  }

  void queryFailsAfterCursorRestart() {
    queryFailsBeforeFirstResponse();
    data.state.rememberDataSource(DATA_SOURCE, data.clock.instant());
    data.state.beginRows(DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL, data.clock.instant());
    data.state.rowsPage(
        DATA_SOURCE,
        NotionPageRefresh.Path.INCREMENTAL,
        Optional.of("expired"),
        Optional.empty(),
        false,
        List.of());
    var request =
        NotionFixtures.JSON
            .createObjectNode()
            .put("page_size", PAGE_SIZE)
            .put("start_cursor", "expired");
    NotionRowQuery.all().applyTo(request);
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
            "{\"code\":\"validation_error\",\"message\":\"The start_cursor provided is invalid: expired\"}")
        .putObject("headers");
    data.server.fault(fault);
  }

  void queryFailsAfterCreationWindowAdvance() {
    queryFailsBeforeFirstResponse();
    var capped = response(Optional.empty(), true);
    capped.putArray("results").add(row(0, BOUNDARY));
    answer(Optional.empty(), Optional.empty(), capped);
  }

  Optional<String> persistedScanField(String field) {
    var checkpoint =
        data.stores.checkpoints().read(NotionAssembly.SOURCE).checkpoint().orElseThrow();
    var scan =
        NotionFixtures.JSON.readTree(checkpoint.valueJson()).at("/incremental/containers/0/scan");
    if (!scan.isObject()) throw new IllegalStateException("재개 가능한 행 진행이 확인점에 없다");
    return Optional.of(scan.path(field)).filter(JsonNode::isString).map(JsonNode::asString);
  }

  int rejectionCount() {
    return data.rejectionCount();
  }

  long queryRequests() {
    return data.server.requests().stream().filter(r -> r.path().endsWith("/query")).count();
  }

  boolean deferred() {
    return data.state.deferred(
        new NotionWork.DataSource(DATA_SOURCE), NotionPageRefresh.Path.INCREMENTAL);
  }

  private void commit(CollectionPage page) {
    data.revision =
        data.commits.commit(data.claim, NotionRefreshScenario.RUN, data.revision, page).revision();
  }

  @Override
  public void close() {
    data.close();
  }
}
