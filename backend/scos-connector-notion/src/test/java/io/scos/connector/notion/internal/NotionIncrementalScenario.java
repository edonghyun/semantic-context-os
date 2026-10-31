package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static io.scos.connector.notion.internal.NotionFixtures.UNKNOWN_PAGE;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Search 명세의 실제 상태·본문 수집·페이지 커밋과 프로토콜 fixture 연결. */
final class NotionIncrementalScenario implements AutoCloseable {
  static final Instant START = NotionScopeScenario.NOW;
  static final String UNREADABLE_PARENT = "unreadable";
  static final String REJECTED_PAGE = "rejected";
  static final String OUTSIDE_PAGE = "outside";
  static final Instant NEXT_EXPLORATION = START.plus(Duration.ofMinutes(15));
  final NotionRefreshScenario data;

  NotionIncrementalScenario() {
    this(new NotionRefreshScenario());
  }

  NotionIncrementalScenario(NotionRefreshScenario data) {
    this.data = data;
    searchPage(SearchPhase.TRASH, Optional.empty(), Optional.empty());
  }

  static final Instant NEXT_LOWER_BOUND = Instant.parse("2026-10-24T23:45:00Z");

  enum PendingCandidate {
    UNKNOWN,
    REJECTED
  }

  void previousRunHasPendingCandidate(PendingCandidate candidate) {
    switch (candidate) {
      case UNKNOWN -> unknownCandidateBeforeRoot();
      case REJECTED -> rejectedCandidateBeforeRoot();
    }
    collect();
    resumeAfter(Duration.between(START, NEXT_EXPLORATION));
  }

  void unresolvedCandidateDuringSameFullRun(PendingCandidate candidate) {
    switch (candidate) {
      case UNKNOWN -> unknownCandidateBeforeRoot();
      case REJECTED -> rejectedCandidateBeforeRoot();
    }
    var interval = Duration.between(START, NEXT_EXPLORATION);
    for (int run = 0; run <= RUNS_TO_LEAVE_OVERLAP; run++) {
      collect();
      data.claims.renew(
          data.claim, NotionRefreshScenario.LEASE_TTL.plus(interval), data.clock.instant());
      data.clock.advance(interval);
    }
  }

  void unresolvedCandidateOutsideOverlap(PendingCandidate candidate) {
    previousRunHasPendingCandidate(candidate);
    for (int run = 0; run < RUNS_TO_LEAVE_OVERLAP; run++) {
      collect();
      resumeAfter(Duration.between(START, NEXT_EXPLORATION));
    }
  }

  void startLaterFullRun() {
    data.state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.SNAPSHOT,
            Optional.of(data.state.checkpoint()),
            data.clock.instant());
    emptySuccessfulExploration();
  }

  void restartFullRun() {
    resumeAfter(Duration.ZERO);
  }

  void restartFromLegacyCheckpoint() {
    var saved = (ObjectNode) NotionFixtures.JSON.readTree(data.state.checkpoint().valueJson());
    saved.withObject("/progress").remove("fullGeneration");
    var path = saved.withObject("/incremental/active");
    var retries = new ArrayList<JsonNode>();
    path.path("retryWork").forEach(retry -> retries.add(retry.path("work")));
    var legacy = path.putArray("retryWork");
    retries.forEach(legacy::add);
    data.state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(new Checkpoint(4, saved.toString())),
            data.clock.instant());
  }

  void collectFull() {
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    new NotionReconciliation(
            NotionRefreshScenario.ROOTS,
            data.state,
            new NotionReconciliation.Dependencies(
                client, data.input, data.clock, data.server.token()))
        .collect(data::commit);
  }

  List<JsonNode> carriedCandidates() {
    var saved = NotionFixtures.JSON.readTree(data.state.checkpoint().valueJson());
    var candidates = new ArrayList<JsonNode>();
    for (SearchPhase phase : SearchPhase.values())
      saved.at("/incremental/" + phase.wire() + "/retryWork").forEach(candidates::add);
    return candidates;
  }

  // 최초 편집이 30분 겹침 밖으로 나가도록 15/30/45분 탐색 뒤 60분 run을 준비한다.
  static final int RUNS_TO_LEAVE_OVERLAP = 3;

  void retryOutsideOverlapBecomesReadable() {
    previousRunHasPendingCandidate(PendingCandidate.UNKNOWN);
    for (int run = 0; run < RUNS_TO_LEAVE_OVERLAP; run++) {
      collect();
      resumeAfter(Duration.between(START, NEXT_EXPLORATION));
    }
    var known = NotionScopeScenario.page(UNKNOWN_PAGE, "page_id", NotionRefreshScenario.PAGE);
    data.server.respond(
        "GET",
        "/v1/pages/" + UNKNOWN_PAGE,
        new Response(HttpURLConnection.HTTP_OK, known.toString(), Map.of()));
    data.bodyPage(UNKNOWN_PAGE, Optional.empty(), Optional.empty());
  }

  private void resumeAfter(Duration elapsed) {
    data.claims.renew(
        data.claim, NotionRefreshScenario.LEASE_TTL.plus(elapsed), data.clock.instant());
    data.clock.advance(elapsed);
    data.state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(data.state.checkpoint()),
            data.clock.instant());
  }

  Optional<String> completedUnknownVersion() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(data.fingerprint(), List.of(UNKNOWN_PAGE), data.input.cancel())
                .get(UNKNOWN_PAGE))
        .flatMap(summary -> summary.completion().map(value -> value.version()));
  }

  List<String> retryPageIds() {
    var retries =
        NotionFixtures.JSON
            .readTree(data.state.checkpoint().valueJson())
            .at("/incremental/active/retryWork");
    var ids = new ArrayList<String>();
    retries.forEach(retry -> ids.add(retry.path("work").path("pageId").asString()));
    return ids;
  }

  Instant nextSearchLowerBound() {
    return data.state.beginSearch(SearchPhase.ACTIVE, data.clock.instant()).lowerBound();
  }

  static final String LATE_VERSION = "2026-10-24T23:45:00Z";

  void rejectedScanWasResumed() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(
        SearchPhase.ACTIVE, Optional.empty(), List.of(new NotionWork.Page(REJECTED_PAGE)));
    data.server.respond("GET", "/v1/pages/" + REJECTED_PAGE, NotionClientScenario.objectNotFound());
    data.state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(data.state.checkpoint()),
            data.clock.instant());
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
    data.emptyBodyAvailable();
  }

  void restartedSearchWasInterrupted() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(
        SearchPhase.ACTIVE, Optional.of("expired"), List.of(new NotionWork.Page(OUTSIDE_PAGE)));
    data.state.restartSearch(SearchPhase.ACTIVE);
    data.state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(data.state.checkpoint()),
            data.clock.instant());
    data.server.respond(
        "GET",
        "/v1/pages/" + OUTSIDE_PAGE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(OUTSIDE_PAGE, "workspace", "").toString(),
            Map.of()));
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
    data.emptyBodyAvailable();
  }

  long rejectedPageRequests() {
    return data.server.requests().stream()
        .filter(r -> r.path().equals("/v1/pages/" + REJECTED_PAGE))
        .count();
  }

  enum DirectRejection {
    BODY,
    METADATA
  }

  void newSearchVersionAfterDirectChildRejection(DirectRejection rejected) {
    data.childPageAvailable();
    var child = NotionScopeScenario.page(CHILD_PAGE, "page_id", NotionRefreshScenario.PAGE);
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, child.toString(), Map.of()));
    String rejectedPath =
        rejected == DirectRejection.BODY
            ? "/v1/blocks/" + CHILD_PAGE + "/children"
            : "/v1/pages/" + CHILD_PAGE;
    data.server.respond("GET", rejectedPath, NotionClientScenario.objectNotFound());
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(SearchPhase.ACTIVE, Optional.of("old-version"), List.of());
    searchPage(SearchPhase.ACTIVE, Optional.of("old-version"), Optional.empty(), data.page());
    collect();
    child.put("last_edited_time", NEXT_EXPLORATION.toString());
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, child.toString(), Map.of()));
    data.bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), child);
  }

  Optional<String> completedChildVersion() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(data.fingerprint(), List.of(CHILD_PAGE), data.input.cancel())
                .get(CHILD_PAGE))
        .flatMap(summary -> summary.completion().map(value -> value.version()));
  }

  void newVersionAfterBodyRejection() {
    data.bodyIsNotFound();
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(SearchPhase.ACTIVE, Optional.of("old-version"), List.of());
    searchPage(SearchPhase.ACTIVE, Optional.of("old-version"), Optional.empty(), data.page());
    collect();
    var edited = data.page().put("last_edited_time", NEXT_EXPLORATION.toString());
    data.answerPage(edited);
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), edited);
    data.emptyBodyAvailable();
  }

  void newParentVersionAfterRejectedChild() {
    repeatedParentHasRejectedDatabase();
    collect();
    data.answerPage(data.page().put("last_edited_time", NEXT_EXPLORATION.toString()));
  }

  void newEditAppearsAfterRejectedSlice() {
    rejectedScanWasResumed();
    collect();
    data.answerPage(data.page().put("last_edited_time", NEXT_EXPLORATION.toString()));
  }

  void collectTwiceInSameRun() {
    collect();
    collect();
  }

  void lateCandidateAppearsAfterWatermark() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, START);
    var elapsed = Duration.between(START, NEXT_EXPLORATION);
    data.claims.renew(
        data.claim, NotionRefreshScenario.LEASE_TTL.plus(elapsed), data.clock.instant());
    data.clock.advance(elapsed);
    var late = data.page().put("last_edited_time", LATE_VERSION);
    data.answerPage(late);
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), late);
    data.emptyBodyAvailable();
  }

  void futureCandidateAvailable() {
    var future = data.page().put("last_edited_time", NEXT_EXPLORATION.toString());
    data.answerPage(future);
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), future);
    data.emptyBodyAvailable();
  }

  void unknownCandidateBeforeRoot() {
    var unknown = NotionScopeScenario.page(UNKNOWN_PAGE, "page_id", UNREADABLE_PARENT);
    data.server.respond(
        "GET",
        "/v1/pages/" + UNKNOWN_PAGE,
        new Response(HttpURLConnection.HTTP_OK, unknown.toString(), Map.of()));
    data.server.respond(
        "GET", "/v1/pages/" + UNREADABLE_PARENT, NotionClientScenario.objectNotFound());
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), unknown, data.page());
    data.emptyBodyAvailable();
  }

  void pendingDataSourceIsTrashed() {
    new NotionDataSourcesScenario(data).pendingDataSourceWasTrashed();
    emptySuccessfulExploration();
  }

  void rejectedParentHasAccessibleChild() {
    var child = data.block(CHILD_PAGE).put("type", "child_page");
    child.remove("paragraph");
    child.putObject("child_page").put("title", "Child");
    data.bodyPage(
        NotionRefreshScenario.PAGE,
        Optional.empty(),
        Optional.empty(),
        child,
        data.block("unshared-body").put("has_children", true));
    data.server.respond(
        "GET", "/v1/blocks/unshared-body/children", NotionClientScenario.objectNotFound());
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(CHILD_PAGE, "page_id", NotionRefreshScenario.PAGE).toString(),
            Map.of()));
    data.bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
  }

  void repeatedParentHasRejectedDatabase() {
    candidateRepeatsAcrossPages();
    data.childDatabaseAvailable();
    data.server.respond("GET", "/v1/databases/database", NotionClientScenario.objectNotFound());
  }

  void knownContainerIsRejected() {
    new NotionDataSourcesScenario(data).rejectedDataSourceBeforeAccessibleSource();
    data.state.rememberDatabase(NotionDataSourcesScenario.DATABASE, START);
    emptySuccessfulExploration();
  }

  void repeatedParentHasRejectedChild() {
    candidateRepeatsAcrossPages();
    data.childPageAvailable();
    var child = NotionScopeScenario.page(CHILD_PAGE, "page_id", NotionRefreshScenario.PAGE);
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, child.toString(), Map.of()));
    data.server.respond(
        "GET", "/v1/blocks/" + CHILD_PAGE + "/children", NotionClientScenario.objectNotFound());
  }

  void rejectedCandidateBeforeRoot() {
    var rejected = NotionScopeScenario.page(REJECTED_PAGE, "page_id", NotionRefreshScenario.PAGE);
    data.server.respond("GET", "/v1/pages/" + REJECTED_PAGE, NotionClientScenario.objectNotFound());
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), rejected, data.page());
    data.emptyBodyAvailable();
  }

  void emptySuccessfulExploration() {
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty());
  }

  Instant nextIncrementalAt() {
    return Instant.parse(
        NotionFixtures.JSON
            .readTree(data.state.checkpoint().valueJson())
            .at("/progress/nextIncrementalAt")
            .asString());
  }

  void childPageOutsideSearch() {
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
    data.childPageAvailable();
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(CHILD_PAGE, "page_id", NotionRefreshScenario.PAGE).toString(),
            Map.of()));
    data.bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
  }

  boolean completedChild() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(data.fingerprint(), List.of(CHILD_PAGE), data.input.cancel())
                .get(CHILD_PAGE))
        .map(summary -> summary.isCompleted(NotionRefreshScenario.VERSION))
        .orElse(false);
  }

  void childDatabaseOutsideSearch() {
    var database = new NotionDataSourcesScenario(data);
    database.oldRowInNewDataSource();
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
    var child = data.block(NotionDataSourcesScenario.DATABASE).put("type", "child_database");
    child.remove("paragraph");
    child.putObject("child_database").put("title", "Inventory");
    data.bodyPage(NotionRefreshScenario.PAGE, Optional.empty(), Optional.empty(), child);
  }

  boolean completedDiscoveredRow() {
    return Optional.ofNullable(
            data.input
                .state()
                .bodies(
                    data.fingerprint(),
                    List.of(NotionDataSourcesScenario.OLD_ROW),
                    data.input.cancel())
                .get(NotionDataSourcesScenario.OLD_ROW))
        .map(summary -> summary.isCompleted(NotionDataSourcesScenario.OLD_VERSION))
        .orElse(false);
  }

  void oldBoundaryHasAnotherApiPage() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, data.clock.instant());
    var old =
        NotionScopeScenario.page("old", "workspace", "")
            .put("last_edited_time", "2026-10-24T23:29:59Z");
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.of("unneeded"), old);
    searchPage(SearchPhase.ACTIVE, Optional.of("unneeded"), Optional.empty());
  }

  void boundaryCandidateAvailable() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, data.clock.instant());
    var boundary = data.page().put("last_edited_time", "2026-10-24T23:30:00Z");
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), boundary);
    data.emptyBodyAvailable();
  }

  Optional<String> completedRootVersion() {
    return data.completedVersion();
  }

  void equalTimestampSpansApiPages() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, data.clock.instant());
    var foreign =
        NotionScopeScenario.page("foreign", "workspace", "")
            .put("last_edited_time", "2026-10-24T23:30:00Z");
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.of("equal-2"), foreign);
    searchPage(
        SearchPhase.ACTIVE,
        Optional.of("equal-2"),
        Optional.empty(),
        data.page().put("last_edited_time", "2026-10-24T23:30:00Z"));
    data.server.respond(
        "GET",
        "/v1/pages/foreign",
        new Response(HttpURLConnection.HTTP_OK, foreign.toString(), Map.of()));
    data.emptyBodyAvailable();
  }

  void candidateRepeatsAcrossPages() {
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.of("duplicate-2"), data.page());
    searchPage(SearchPhase.ACTIVE, Optional.of("duplicate-2"), Optional.empty(), data.page());
    data.emptyBodyAvailable();
  }

  long rootMetadataRequests() {
    return data.pageRequests();
  }

  void wrongPhaseBeforeBoundary() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, data.clock.instant());
    data.clock.advance(Duration.ofMinutes(1));
    var wrong = data.page().put("in_trash", true).put("last_edited_time", "2026-10-24T23:29:59Z");
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.of("unused"), wrong);
  }

  void expiredSearchCursor() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(SearchPhase.ACTIVE, Optional.of("expired"), List.of());
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(
            HttpURLConnection.HTTP_BAD_REQUEST,
            "{\"code\":\"validation_error\",\"message\":\"The start_cursor provided is invalid: expired\"}",
            Map.of()));
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), data.page());
    data.emptyBodyAvailable();
  }

  void repeatedlyExpiredSearchCursor() {
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(
            HttpURLConnection.HTTP_BAD_REQUEST,
            "{\"code\":\"validation_error\",\"message\":\"The start_cursor provided is invalid: expired\"}",
            Map.of()));
    var fault = activeSearchFault(Optional.empty());
    var listed =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", true)
            .put("next_cursor", "expired");
    listed.putArray("results");
    var responses = fault.putArray("responses");
    // 원래 요청과 세 번의 재시작을 허용하며, 무한 재시도 변이는 다음 인증 오류로 중단한다.
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

  void searchFailsAfterCursorRestart() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(SearchPhase.ACTIVE, Optional.of("expired"), List.of());
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(HttpURLConnection.HTTP_UNAUTHORIZED, "{\"code\":\"unauthorized\"}", Map.of()));
    var fault = activeSearchFault(Optional.of("expired"));
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

  void searchFailsAfterCompletedCandidate() {
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(HttpURLConnection.HTTP_UNAUTHORIZED, "{\"code\":\"unauthorized\"}", Map.of()));
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.of("next-fails"), data.page());
    data.emptyBodyAvailable();
  }

  private ObjectNode activeSearchFault(Optional<String> cursor) {
    var body = NotionFixtures.JSON.createObjectNode().put("page_size", 100);
    body.putObject("filter").put("property", "object").put("value", "page");
    body.putObject("sort").put("direction", "descending").put("timestamp", "last_edited_time");
    cursor.ifPresent(value -> body.put("start_cursor", value));
    var fault =
        NotionFixtures.JSON.createObjectNode().put("method", "POST").put("path", "/v1/search");
    fault.set("body", body);
    return fault;
  }

  private JsonNode persistedActiveScan() {
    var saved = data.stores.checkpoints().read(NotionAssembly.SOURCE).checkpoint().orElseThrow();
    var scan = NotionFixtures.JSON.readTree(saved.valueJson()).at("/incremental/active/scan");
    if (!scan.isObject()) throw new IllegalStateException("재개 가능한 Search 진행이 확인점에 없다");
    return scan;
  }

  Optional<String> persistedActiveCursor() {
    return Optional.of(persistedActiveScan().path("cursor"))
        .filter(JsonNode::isString)
        .map(JsonNode::asString);
  }

  List<String> persistedActiveWork() {
    var work = new ArrayList<String>();
    persistedActiveScan().path("work").forEach(step -> work.add(NotionWork.read(step).id()));
    return List.copyOf(work);
  }

  void incompleteOldResponse() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.completeSearch(SearchPhase.ACTIVE, data.clock.instant());
    data.clock.advance(Duration.ofMinutes(1));
    var result =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    result.putArray("results").add(data.page().put("last_edited_time", "2026-10-24T23:29:59Z"));
    result.putObject("request_status").put("type", "incomplete");
    data.server.respond(
        "POST", "/v1/search", new Response(HttpURLConnection.HTTP_OK, result.toString(), Map.of()));
  }

  void unrelatedValidationError() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(SearchPhase.ACTIVE, Optional.of("cursor"), List.of());
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(
            HttpURLConnection.HTTP_BAD_REQUEST,
            "{\"code\":\"validation_error\",\"message\":\"Invalid filter property\"}",
            Map.of()));
  }

  void pendingLastCandidate() {
    data.state.beginSearch(SearchPhase.ACTIVE, START);
    data.state.searchPage(
        SearchPhase.ACTIVE,
        Optional.empty(),
        List.of(new NotionWork.Page(NotionRefreshScenario.PAGE)));
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty());
    data.emptyBodyAvailable();
  }

  void unorderedResponse() {
    var old = data.page().put("last_edited_time", "2026-10-24T23:29:59Z");
    searchPage(SearchPhase.ACTIVE, Optional.empty(), Optional.empty(), old, data.page());
    data.emptyBodyAvailable();
  }

  Optional<Instant> activeWatermark() {
    return data.state.watermark(SearchPhase.ACTIVE);
  }

  void searchPage(
      SearchPhase phase, Optional<String> cursor, Optional<String> next, JsonNode... pages) {
    var body = NotionFixtures.JSON.createObjectNode();
    var filter = body.putObject("filter").put("property", "object").put("value", "page");
    if (phase == SearchPhase.TRASH) filter.put("in_trash", true);
    body.putObject("sort").put("direction", "descending").put("timestamp", "last_edited_time");
    body.put("page_size", 100);
    cursor.ifPresent(c -> body.put("start_cursor", c));
    var result =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", next.isPresent());
    if (next.isPresent()) result.put("next_cursor", next.get());
    else result.putNull("next_cursor");
    var entries = result.putArray("results");
    for (JsonNode page : pages) entries.add(page);
    var fault =
        NotionFixtures.JSON.createObjectNode().put("method", "POST").put("path", "/v1/search");
    fault.set("body", body);
    fault
        .putArray("responses")
        .addObject()
        .put("status", HttpURLConnection.HTTP_OK)
        .put("text", result.toString())
        .putObject("headers");
    data.server.fault(fault);
  }

  void collect() {
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(data.input, data.clock);
    var refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client,
                calls,
                new NotionMutations(data.input.content(), data.clock),
                data.input,
                data.state,
                data::commit,
                data.server.token()));
    new NotionIncrementalCollection(
            new NotionIncrementalCollection.Dependencies(
                client,
                calls,
                new NotionMutations(data.input.content(), data.clock),
                data.input,
                data.state,
                data::commit,
                data.server.token(),
                data.clock,
                refresh))
        .collect();
  }

  long activeSearchRequests() {
    return data.server.requests().stream()
        .filter(r -> r.path().equals("/v1/search"))
        .filter(r -> !NotionFixtures.JSON.readTree(r.body()).at("/filter/in_trash").asBoolean())
        .count();
  }

  @Override
  public void close() {
    data.close();
  }
}
