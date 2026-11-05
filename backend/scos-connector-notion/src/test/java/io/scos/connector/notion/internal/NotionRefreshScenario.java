package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;

import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.CollectionStores;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.ReconciliationDiscovery.Classification;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.memory.MemoryBodyCompletionStore;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceOrder;
import io.scos.support.FakeHttpServer.Response;
import io.scos.support.TestMetrics;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 가짜 HTTP와 계약 검증된 메모리 저장소 위의 실제 페이지 커밋. */
final class NotionRefreshScenario implements AutoCloseable {
  static final String PAGE = "root";
  static final String COMPACT_PAGE_ID = "11111111222233334444555555555555";
  static final String CANONICAL_PAGE_ID = "11111111-2222-3333-4444-555555555555";
  static final String LATEST_VERSION = "2026-10-25T00:01:00Z";
  static final String VERSION = NotionScopeScenario.NOW.toString();
  static final Roots ROOTS = NotionFixtures.roots(PAGE);
  static final RunId RUN = new RunId("refresh-run");
  static final Duration LEASE_TTL = Duration.ofMinutes(10);
  final FakeNotionServer server = NotionAssembly.server();
  final MutableClock clock = new MutableClock(NotionScopeScenario.NOW);
  final MemoryRawItemStore raw = new MemoryRawItemStore();
  final MemoryBodyCompletionStore bodies = new MemoryBodyCompletionStore();
  final MemoryRunStore runs = MemoryRunStore.create();
  final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  NotionReconciliationState state =
      NotionReconciliationState.read(
          NotionFixtures.JSON,
          ROOTS,
          CollectionMode.INCREMENTAL,
          Optional.empty(),
          clock.instant());
  final List<CollectionPage> committed = new ArrayList<>();
  final WorkClaim claim =
      claims
          .tryClaim(
              NotionAssembly.SOURCE, new OwnerId("refresh-worker"), LEASE_TTL, clock.instant())
          .orElseThrow();
  final PageCommit commits;
  final CollectionStores stores;
  final CollectionInput input;
  boolean cleanedBeforeLatestVersion;
  boolean cancelAfterMetadata;
  long revision;
  List<NotionWork> discovered = List.of();
  NotionPageRefresh.Result result;

  NotionRefreshScenario() {
    runs.start(RUN, NotionAssembly.SOURCE, clock.instant());
    stores =
        new CollectionStores(
                TransactionOperations.withoutTransaction(),
                claims,
                runs,
                raw,
                new MemoryCheckpointPort(),
                new MemoryOutboxPort(),
                new MemorySourceChangeLog(),
                raw.acls(),
                runs.accessObservations())
            .withBodies(bodies);
    commits = new PageCommit(stores, clock, TestMetrics.collection(), new Progress(clock));
    input =
        new CollectionInput(
            NotionAssembly.instance("{\"rootPageIds\":[\"root\"]}", clock.instant()).descriptor(),
            Optional.empty(),
            CollectionMode.INCREMENTAL,
            NotionAssembly.recordingExecutor(
                NotionClientScenario.TIMEOUT, clock, new ArrayList<>()),
            RawContentCapture.defaults(),
            CancellationToken.none(),
            new RunCompletion(
                    stores,
                    new SnapshotShrinkGuard(
                        new MemorySourceInstanceStore(),
                        new MemorySnapshotGuardStore(),
                        SnapshotShrinkLimits.DEFAULTS,
                        TestMetrics.collection()))
                .state(NotionAssembly.SOURCE, RUN, Optional.empty()));
    answerPage(page());
  }

  ObjectNode page() {
    return NotionScopeScenario.page(PAGE, "workspace", "");
  }

  void answerPage(ObjectNode page) {
    server.respond(
        "GET",
        "/v1/pages/" + PAGE,
        new Response(HttpURLConnection.HTTP_OK, page.toString(), Map.of()));
  }

  void sameBodyIsCompleted() {
    raw.apply(NotionAssembly.SOURCE, new NotionMutations(input.content(), clock).page(page()));
    bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            fingerprint(),
            PAGE,
            VERSION,
            new SourceOrder("notion.page-state.v1", "2026-10-25T00:00:00.000Z"),
            clock.instant(),
            Set.of()));
  }

  static final Instant SUCCESSFUL_SEARCH = Instant.parse("2026-10-24T23:30:00Z");
  static final String MISSED_VERSION = "2026-10-24T23:00:00Z";
  static final String PREVIOUS_VERSION = "2026-10-24T22:00:00Z";

  void oldMissWithinConfiguredOverlap() {
    oldPageWasMissedBySuccessfulSearch();
    var roots =
        (Roots)
            NotionConfiguration.read(
                NotionFixtures.JSON,
                new SourceConfig(2, "{\"rootPageIds\":[\"root\"],\"overlapSeconds\":3600}"));
    state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            roots,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            clock.instant());
  }

  void oldPageWasMissedBySuccessfulSearch() {
    var previous = page().put("last_edited_time", PREVIOUS_VERSION);
    var mutation = (RawMutation.Upsert) new NotionMutations(input.content(), clock).page(previous);
    raw.apply(NotionAssembly.SOURCE, mutation);
    bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            fingerprint(),
            PAGE,
            PREVIOUS_VERSION,
            mutation.item().sourceOrder().orElseThrow(),
            clock.instant(),
            Set.of()));
    runs.recordSnapshotCompletion(
        NotionAssembly.SOURCE, SUCCESSFUL_SEARCH, 0, Optional.of(fingerprint()));
    state.checkBaseline(input.state(), input.cancel());
    state.beginSearch(SearchPhase.ACTIVE, SUCCESSFUL_SEARCH);
    state.completeSearch(SearchPhase.ACTIVE, clock.instant());
    answerPage(page().put("last_edited_time", MISSED_VERSION));
    emptyBodyAvailable();
  }

  static final String ROW_SOURCE = "row-source";

  void oldPageWasMissedBySuccessfulTrashSearch() {
    oldPageWasMissedBySuccessfulSearch();
    state.beginSearch(SearchPhase.TRASH, SUCCESSFUL_SEARCH);
    state.completeSearch(SearchPhase.TRASH, clock.instant());
    answerPage(page().put("last_edited_time", MISSED_VERSION).put("in_trash", true));
  }

  void pageBelongsToNewDataSource() {
    oldPageWasMissedBySuccessfulSearch();
    state.rememberDataSource(ROW_SOURCE, clock.instant());
    var row = page().put("last_edited_time", MISSED_VERSION);
    row.set(
        "parent",
        NotionFixtures.JSON
            .createObjectNode()
            .put("type", "data_source_id")
            .put("data_source_id", ROW_SOURCE));
    answerPage(row);
  }

  void newSubtreeInEstablishedGeneration() {
    oldPageWasMissedBySuccessfulSearch();
    server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(CHILD_PAGE, "page_id", PAGE)
                .put("last_edited_time", MISSED_VERSION)
                .toString(),
            Map.of()));
    bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
  }

  void pageBelongsToDataSourceWithOnlyFullSeed() {
    pageBelongsToNewDataSource();
    state.beginRows(ROW_SOURCE, NotionPageRefresh.Path.FULL, clock.instant());
    state.completeRows(ROW_SOURCE, NotionPageRefresh.Path.FULL, clock.instant());
  }

  void pageBelongsToEstablishedDataSource() {
    pageBelongsToNewDataSource();
    var started = Instant.parse(MISSED_VERSION);
    state.beginRows(ROW_SOURCE, NotionPageRefresh.Path.INCREMENTAL, started);
    state.completeRows(ROW_SOURCE, NotionPageRefresh.Path.INCREMENTAL, clock.instant());
  }

  void bodyAwaitingReading(NotionPageRefresh.Path path) {
    oldPageWasMissedBySuccessfulSearch();
    if (path == NotionPageRefresh.Path.INCREMENTAL) collect(NotionPageRefresh.Path.FULL);
  }

  Optional<String> lastReadingPath() {
    return bodies
        .find(NotionAssembly.SOURCE, fingerprint(), PAGE)
        .flatMap(BodyCompletionStore.Body::lastReading)
        .map(read -> read.path().name());
  }

  List<Classification> discoveries() {
    return bodies.discoveries(NotionAssembly.SOURCE, RUN).stream()
        .flatMap(discovery -> discovery.classification().stream())
        .toList();
  }

  String fingerprint() {
    return NotionFixtures.JSON
        .readTree(state.checkpoint().valueJson())
        .at("/scope/configFingerprint")
        .asString();
  }

  void collect(NotionPageRefresh.Path path) {
    collectPage(PAGE, path);
  }

  void restartCompletedRead() {
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(committed.getLast().nextCheckpoint()),
            clock.instant());
    var client = NotionAssembly.client(server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(input, clock);
    var refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client,
                calls,
                new NotionMutations(input.content(), clock),
                input,
                resumed,
                this::commit,
                server.token()));
    result =
        refresh.refresh(
            PAGE,
            NotionPageRefresh.Path.INCREMENTAL,
            new NotionScope(ROOTS, client, calls, server.token()));
    discovered = result.discovered();
  }

  void commit(CollectionPage page) {
    revision = commits.commit(claim, RUN, revision, page).revision();
    committed.add(page);
    if (cancelAfterMetadata
        && page.mutations().stream().anyMatch(m -> m.externalId().equals(PAGE))
        && page.bodies().stream().anyMatch(b -> b.outcome() == BodyObservation.Outcome.ENUMERATING))
      input.cancel().cancel();
    if (raw.get(NotionAssembly.SOURCE, "block:ghost").orElse(null) instanceof RawRecord.Deleted
        && !completedVersion().filter(LATEST_VERSION::equals).isPresent())
      cleanedBeforeLatestVersion = true;
  }

  void emptyBodyAvailable() {
    server.respond(
        "GET",
        "/v1/blocks/" + PAGE + "/children",
        new Response(
            HttpURLConnection.HTTP_OK,
            "{\"object\":\"list\",\"type\":\"block\",\"results\":[],\"has_more\":false,\"next_cursor\":null}",
            Map.of()));
  }

  void pageUuidAliasAvailable() {
    var page = NotionScopeScenario.page(CANONICAL_PAGE_ID, "page_id", PAGE);
    for (String id : List.of(COMPACT_PAGE_ID, CANONICAL_PAGE_ID))
      server.respond(
          "GET",
          "/v1/pages/" + id,
          new Response(HttpURLConnection.HTTP_OK, page.toString(), Map.of()));
    bodyPage(CANONICAL_PAGE_ID, Optional.empty(), Optional.empty());
  }

  Optional<String> aliasedPageCompletion() {
    return bodies
        .find(NotionAssembly.SOURCE, fingerprint(), CANONICAL_PAGE_ID)
        .map(BodyCompletionStore.Body::version);
  }

  Optional<String> completedVersion() {
    return bodies
        .find(NotionAssembly.SOURCE, fingerprint(), PAGE)
        .map(BodyCompletionStore.Body::version);
  }

  void pagedBodyAvailable() {
    bodyPage(PAGE, Optional.empty(), Optional.of("body-2"), block("one"));
    bodyPage(PAGE, Optional.of("body-2"), Optional.empty(), block("two"));
  }

  void nestedBodyAvailable() {
    bodyPage(PAGE, Optional.empty(), Optional.empty(), block("nested").put("has_children", true));
    bodyPage("nested", Optional.empty(), Optional.empty(), block("leaf"));
  }

  void childPageAvailable() {
    var child = block(CHILD_PAGE).put("type", "child_page").put("has_children", true);
    child.remove("paragraph");
    child.putObject("child_page").put("title", "Child");
    bodyPage(PAGE, Optional.empty(), Optional.empty(), child);
  }

  void childDatabaseAvailable() {
    var database = block("database").put("type", "child_database");
    database.remove("paragraph");
    database.putObject("child_database").put("title", "Database");
    bodyPage(PAGE, Optional.empty(), Optional.empty(), database);
  }

  ObjectNode block(String id) {
    var block = NotionScopeScenario.object("block", id, "page_id", PAGE).put("has_children", false);
    block.putObject("paragraph").putArray("rich_text");
    return block;
  }

  void bodyPage(String owner, Optional<String> cursor, Optional<String> next, JsonNode... blocks) {
    var value =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "block")
            .put("has_more", next.isPresent());
    if (next.isPresent()) value.put("next_cursor", next.get());
    else value.putNull("next_cursor");
    var results = value.putArray("results");
    for (JsonNode block : blocks) results.add(block);
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/blocks/" + owner + "/children");
    var params = fault.putObject("params").put("page_size", "100");
    cursor.ifPresent(c -> params.put("start_cursor", c));
    var response =
        fault
            .putArray("responses")
            .addObject()
            .put("status", HttpURLConnection.HTTP_OK)
            .put("text", value.toString());
    response.putObject("headers");
    server.fault(fault);
  }

  Set<String> completedBlocks() {
    return bodies
        .find(NotionAssembly.SOURCE, fingerprint(), PAGE)
        .map(BodyCompletionStore.Body::blocks)
        .orElse(Set.of());
  }

  void pageChangesDuringBodyRead() {
    sameBodyIsCompleted();
    var mutations = new NotionMutations(input.content(), clock);
    raw.apply(NotionAssembly.SOURCE, mutations.block(PAGE, block("ghost")));
    raw.apply(NotionAssembly.SOURCE, mutations.block(PAGE, block("kept")));
    bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            fingerprint(),
            PAGE,
            "2026-10-24T23:59:00Z",
            new SourceOrder("notion.page-state.v1", "2026-10-24T23:59:00.000Z"),
            clock.instant(),
            Set.of("block:ghost", "block:kept")));
    bodyPage(PAGE, Optional.empty(), Optional.empty(), block("kept"));
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/pages/" + PAGE);
    var replies = fault.putArray("responses");
    for (var value : List.of(page(), page().put("last_edited_time", LATEST_VERSION))) {
      replies
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", value.toString())
          .putObject("headers");
    }
    server.fault(fault);
  }

  void pageKeepsChanging() {
    emptyBodyAvailable();
    final int editingResponses = 10; // 3회 열거 한도를 넘긴 뒤에도 응답은 유한하게 끝나도록 한다.
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/pages/" + PAGE);
    var replies = fault.putArray("responses");
    for (int edit = 0; edit < editingResponses; edit++) {
      var value =
          page().put("last_edited_time", clock.instant().plus(Duration.ofMinutes(edit)).toString());
      replies
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", value.toString())
          .putObject("headers");
    }
    server.fault(fault);
  }

  String cursorCommittedWithFirstBlock() {
    return committed.stream()
        .filter(page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one")))
        .map(
            page ->
                NotionFixtures.JSON
                    .readTree(page.nextCheckpoint().valueJson())
                    .at("/refreshes/0/work/0/cursor")
                    .asString())
        .findFirst()
        .orElse("");
  }

  void bodyShrinkIsGuarded() {
    sameBodyIsCompleted();
    final int referenceBodySize = 10; // 기본 부분 축소 보호의 비율 적용 최소 규모.
    var ids = new HashSet<String>();
    var mutations = new NotionMutations(input.content(), clock);
    for (int index = 0; index < referenceBodySize; index++) {
      String id = "old-" + index;
      raw.apply(NotionAssembly.SOURCE, mutations.block(PAGE, block(id)));
      ids.add("block:" + id);
    }
    bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            fingerprint(),
            PAGE,
            "2026-10-24T23:59:00Z",
            new SourceOrder("notion.page-state.v1", "2026-10-24T23:59:00.000Z"),
            clock.instant(),
            ids));
    bodyPage(PAGE, Optional.empty(), Optional.empty(), block("old-0"));
  }

  void wrongPageAtFinish() {
    emptyBodyAvailable();
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/pages/" + PAGE);
    var replies = fault.putArray("responses");
    for (var value : List.of(page(), page().put("id", "another-page"))) {
      replies
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", value.toString())
          .putObject("headers");
    }
    server.fault(fault);
  }

  void staleHandoffBeforeTrash() {
    state.bodyFinished(PAGE, VERSION, List.of(new NotionWork.Page(CHILD_PAGE)));
    explicitTrashAvailable();
  }

  List<NotionWork> savedBodyHandoff() {
    var saved = committed.getLast().nextCheckpoint();
    var resumed =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(saved),
            clock.instant());
    return resumed.bodyHandoff(PAGE, VERSION);
  }

  void newMetadataAfterDeferredBody(NotionPageRefresh.Path path) {
    state.observePage(page(), path);
    state.defer(new NotionWork.Page(PAGE), path);
    answerPage(page().put("last_edited_time", LATEST_VERSION));
    emptyBodyAvailable();
  }

  boolean bodyDeferred(NotionPageRefresh.Path path) {
    return state.deferred(new NotionWork.Page(PAGE), path);
  }

  void explicitTrashAvailable() {
    sameBodyIsCompleted();
    answerPage(page().put("in_trash", true).put("last_edited_time", LATEST_VERSION));
  }

  boolean pageIsDeleted() {
    return raw.get(NotionAssembly.SOURCE, PAGE).orElse(null) instanceof RawRecord.Deleted;
  }

  void knownTrashChildAvailable() {
    var mutations = new NotionMutations(input.content(), clock);
    raw.apply(
        NotionAssembly.SOURCE,
        mutations.page(NotionScopeScenario.page(CHILD_PAGE, "page_id", PAGE)));
    bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            fingerprint(),
            CHILD_PAGE,
            VERSION,
            new SourceOrder("notion.page-state.v1", "2026-10-25T00:00:00.000Z"),
            clock.instant(),
            Set.of()));
    var trash =
        NotionScopeScenario.page(CHILD_PAGE, "page_id", "unreadable")
            .put("in_trash", true)
            .put("last_edited_time", LATEST_VERSION);
    server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, trash.toString(), Map.of()));
    server.respond("GET", "/v1/pages/unreadable", NotionClientScenario.objectNotFound());
  }

  void collectPage(String pageId, NotionPageRefresh.Path path) {
    var client = NotionAssembly.client(server.baseUrl(), NotionClientScenario.TIMEOUT);
    var calls = new NotionCalls(input, clock);
    var refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client,
                calls,
                new NotionMutations(input.content(), clock),
                input,
                state,
                this::commit,
                server.token()));
    result = refresh.refresh(pageId, path, new NotionScope(ROOTS, client, calls, server.token()));
    discovered = result.discovered();
  }

  boolean childIsDeleted() {
    return raw.get(NotionAssembly.SOURCE, CHILD_PAGE).orElse(null) instanceof RawRecord.Deleted;
  }

  void missingChildAvailable() {
    server.respond("GET", "/v1/pages/missing", NotionClientScenario.objectNotFound());
  }

  int rejectionCount() {
    return runs.rejections(RUN).size();
  }

  NotionPageRefresh.Incompletion incompletionReason() {
    return ((NotionPageRefresh.Pending) result).reason();
  }

  void childDisappearsAtFinish() {
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/pages/" + CHILD_PAGE);
    var replies = fault.putArray("responses");
    replies
        .addObject()
        .put("status", HttpURLConnection.HTTP_OK)
        .put("text", NotionScopeScenario.page(CHILD_PAGE, "page_id", PAGE).toString())
        .putObject("headers");
    replies
        .addObject()
        .put("status", HttpURLConnection.HTTP_NOT_FOUND)
        .put("text", "{\"object\":\"error\",\"code\":\"object_not_found\"}")
        .putObject("headers");
    server.fault(fault);
    bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
  }

  void sameVersionBodyIsIncomplete() {
    sameBodyIsCompleted();
    raw.apply(
        NotionAssembly.SOURCE,
        new NotionMutations(input.content(), clock).block(PAGE, block("ghost")));
    bodies.savePending(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Pending(
            fingerprint(),
            PAGE,
            VERSION,
            new SourceOrder("notion.page-state.v1", "2026-10-25T00:00:00.000Z"),
            new RunId("interrupted-run"),
            Set.of("block:ghost")));
  }

  void bodyIsNotFound() {
    sameVersionBodyIsIncomplete();
    server.respond(
        "GET", "/v1/blocks/" + PAGE + "/children", NotionClientScenario.objectNotFound());
  }

  void bodyResponseIsIncomplete() {
    sameVersionBodyIsIncomplete();
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "block")
            .put("has_more", false)
            .putNull("next_cursor");
    response.putArray("results").add(block("unapplied"));
    response.putObject("request_status").put("type", "incomplete");
    server.respond(
        "GET",
        "/v1/blocks/" + PAGE + "/children",
        new Response(HttpURLConnection.HTTP_OK, response.toString(), Map.of()));
  }

  void cancelsAfterMetadataCommit() {
    sameVersionBodyIsIncomplete();
    emptyBodyAvailable();
    cancelAfterMetadata = true;
  }

  void unknownTrashChildAvailable() {
    var trash =
        NotionScopeScenario.page(CHILD_PAGE, "page_id", "unreadable")
            .put("in_trash", true)
            .put("last_edited_time", LATEST_VERSION);
    server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, trash.toString(), Map.of()));
    server.respond("GET", "/v1/pages/unreadable", NotionClientScenario.objectNotFound());
  }

  void foreignPageAvailable() {
    var foreign = NotionScopeScenario.page("foreign", "workspace", "");
    server.respond(
        "GET",
        "/v1/pages/foreign",
        new Response(HttpURLConnection.HTTP_OK, foreign.toString(), Map.of()));
  }

  void knownChildMovesOutside() {
    knownTrashChildAvailable();
    var outside =
        NotionScopeScenario.page(CHILD_PAGE, "workspace", "")
            .put("last_edited_time", LATEST_VERSION);
    server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(HttpURLConnection.HTTP_OK, outside.toString(), Map.of()));
  }

  boolean ghostIsActive() {
    return raw.get(NotionAssembly.SOURCE, "block:ghost").orElse(null) instanceof RawRecord.Active;
  }

  boolean rawExists(String id) {
    return raw.get(NotionAssembly.SOURCE, id).isPresent();
  }

  boolean childIsActive() {
    return raw.get(NotionAssembly.SOURCE, CHILD_PAGE).orElse(null) instanceof RawRecord.Active;
  }

  long pageRequests() {
    return server.requests().stream().filter(r -> r.path().equals("/v1/pages/" + PAGE)).count();
  }

  long bodyRequests() {
    return server.requests().stream().filter(r -> r.path().endsWith("/children")).count();
  }

  @Override
  public void close() {
    server.close();
  }
}
