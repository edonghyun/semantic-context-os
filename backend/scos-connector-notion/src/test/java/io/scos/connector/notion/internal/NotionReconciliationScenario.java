package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;

import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.support.FakeHttpServer.Response;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/** 가짜 시계·갱신된 같은 lease·실제 수집 커밋의 전체/증분 실행 연결. */
final class NotionReconciliationScenario implements AutoCloseable {
  static final Duration ELAPSED_UNTIL_DUE = Duration.ofMinutes(16);
  static final Duration RENEWED_LEASE_TTL = Duration.ofHours(1);
  static final String SLICE_BOUNDARY_CURSOR = "cycle-b";
  static final String CHANGED_PAGE = "changed-during-full";
  final NotionRefreshScenario data = new NotionRefreshScenario();
  NotionReconciliationState state = data.state;
  Predicate<CollectionPage> advanceAt = page -> false;
  boolean changedBodyWasCompleteBeforeFullContinued;

  static final String FIRST_ROW = "first-before-move";
  static final String LATER_ROW = "later-after-move";

  void databaseInFullTree() {
    var sources = new NotionDataSourcesScenario(data);
    sources.rows();
    var child = data.block(NotionDataSourcesScenario.DATABASE).put("type", "child_database");
    child.remove("paragraph");
    child.putObject("child_database").put("title", "Inventory");
    data.bodyPage(NotionRefreshScenario.PAGE, Optional.empty(), Optional.empty(), child);
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
  }

  void newDataSourceCatchUpCrossesSlice() {
    databaseInFullTree();
    var sources = new NotionDataSourcesScenario(data);
    sources.oldRowInNewDataSource();
    data.claims.renew(data.claim, RENEWED_LEASE_TTL, data.clock.instant());
    advanceAt =
        page ->
            page.bodies().stream()
                .anyMatch(
                    body ->
                        body.pageId().equals(NotionDataSourcesScenario.OLD_ROW)
                            && body.outcome() == BodyObservation.Outcome.CONFIRMED);
  }

  boolean fullTraversalFinished() {
    return state.fullCheckpoint().isEmpty();
  }

  List<String> containerDiscoveries() {
    return data.bodies.discoveries(NotionAssembly.SOURCE, NotionRefreshScenario.RUN).stream()
        .filter(discovery -> discovery.kind() == ReconciliationKind.CONTAINER)
        .map(discovery -> discovery.resourceId())
        .toList();
  }

  void databaseMovesOutsideDuringIncremental() {
    var sources = new NotionDataSourcesScenario(data);
    sources.rows(
        sources.row(FIRST_ROW, NotionDataSourcesScenario.OLD_VERSION),
        sources.row(LATER_ROW, NotionDataSourcesScenario.OLD_VERSION));
    var child = data.block(NotionDataSourcesScenario.DATABASE).put("type", "child_database");
    child.remove("paragraph");
    child.putObject("child_database").put("title", "Inventory");
    data.bodyPage(NotionRefreshScenario.PAGE, Optional.empty(), Optional.empty(), child);
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
    data.claims.renew(data.claim, RENEWED_LEASE_TTL, data.clock.instant());
    advanceAt =
        page -> {
          if (page.bodies().stream()
              .noneMatch(
                  body ->
                      body.pageId().equals(FIRST_ROW)
                          && body.outcome() == BodyObservation.Outcome.COMPLETE)) return false;
          sources.metadata(
              "/v1/databases/" + NotionDataSourcesScenario.DATABASE,
              NotionScopeScenario.object(
                  "database", NotionDataSourcesScenario.DATABASE, "workspace", ""));
          return true;
        };
  }

  boolean laterRowWasStored() {
    return data.raw.get(NotionAssembly.SOURCE, LATER_ROW).isPresent();
  }

  void changedPageAppearsDuringFullBody() {
    data.pagedBodyAvailable();
    var changed =
        NotionScopeScenario.page(CHANGED_PAGE, "page_id", NotionRefreshScenario.PAGE)
            .put("last_edited_time", changedVersion());
    data.server.respond(
        "GET",
        "/v1/pages/" + CHANGED_PAGE,
        new Response(HttpURLConnection.HTTP_OK, changed.toString(), Map.of()));
    data.bodyPage(CHANGED_PAGE, Optional.empty(), Optional.empty());
    search(SearchPhase.ACTIVE, response(), response(changed), response());
    search(SearchPhase.TRASH, response());
    data.claims.renew(data.claim, RENEWED_LEASE_TTL, data.clock.instant());
    advanceAt = page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
  }

  void nestedPageTree() {
    data.childPageAvailable();
    var childBlock = data.block("grandchild").put("type", "child_page");
    childBlock.remove("paragraph");
    childBlock.putObject("child_page").put("title", "Grandchild");
    childBlock.withObject("/parent").put("page_id", CHILD_PAGE);
    data.bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty(), childBlock);
    data.bodyPage("grandchild", Optional.empty(), Optional.empty());
    for (var parent :
        Map.of(CHILD_PAGE, NotionRefreshScenario.PAGE, "grandchild", CHILD_PAGE).entrySet())
      data.server.respond(
          "GET",
          "/v1/pages/" + parent.getKey(),
          new Response(
              HttpURLConnection.HTTP_OK,
              NotionScopeScenario.page(parent.getKey(), "page_id", parent.getValue()).toString(),
              Map.of()));
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
  }

  boolean hasChildHandoffs() {
    return NotionFixtures.JSON.readTree(state.checkpoint().valueJson()).has("refreshes");
  }

  void restartedAtChildPage() {
    var traversal = state.fullTraversal().orElseThrow();
    state.fullProgress(
        traversal
            .completeWork(Optional.empty(), List.of(new NotionWork.Page(CHILD_PAGE)))
            .checkpoint());
    state =
        NotionReconciliationState.read(
            NotionFixtures.JSON,
            NotionRefreshScenario.ROOTS,
            CollectionMode.INCREMENTAL,
            Optional.of(state.checkpoint()),
            data.clock.instant());
    data.server.respond(
        "GET",
        "/v1/pages/" + CHILD_PAGE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(CHILD_PAGE, "page_id", NotionRefreshScenario.PAGE).toString(),
            Map.of()));
    data.bodyPage(CHILD_PAGE, Optional.empty(), Optional.empty());
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
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

  long rootBodyRequests() {
    return data.server.requests().stream()
        .filter(r -> r.path().equals("/v1/blocks/" + NotionRefreshScenario.PAGE + "/children"))
        .count();
  }

  void requiredDataSourceFails() {
    new NotionDataSourcesScenario(data).secondDataSourceFails();
    state.rememberDatabase(NotionDataSourcesScenario.DATABASE, data.clock.instant());
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
  }

  Instant nextIncrementalAt() {
    return Instant.parse(
        NotionFixtures.JSON
            .readTree(state.checkpoint().valueJson())
            .at("/progress/nextIncrementalAt")
            .asString());
  }

  void cyclicFullCursorCrossesIncrementalBoundary() {
    data.server.cycleCursor(
        "GET", "/v1/blocks/" + NotionRefreshScenario.PAGE + "/children", "block");
    search(SearchPhase.ACTIVE, response());
    search(SearchPhase.TRASH, response());
    data.claims.renew(data.claim, RENEWED_LEASE_TTL, data.clock.instant());
    advanceAt =
        page ->
            NotionFixtures.JSON
                .readTree(page.nextCheckpoint().valueJson())
                .at("/refreshes/0/work/0/cursor")
                .asString()
                .equals(SLICE_BOUNDARY_CURSOR);
  }

  long activeSearchRequests() {
    return data.server.requests().stream()
        .filter(r -> r.path().equals("/v1/search"))
        .filter(r -> !NotionFixtures.JSON.readTree(r.body()).at("/filter/in_trash").asBoolean())
        .count();
  }

  String changedVersion() {
    return NotionIncrementalScenario.START.plus(ELAPSED_UNTIL_DUE).toString();
  }

  private JsonNode response(JsonNode... pages) {
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "page_or_data_source")
            .put("has_more", false)
            .putNull("next_cursor");
    var entries = response.putArray("results");
    for (JsonNode page : pages) entries.add(page);
    return response;
  }

  void search(SearchPhase phase, JsonNode... responses) {
    var body = NotionFixtures.JSON.createObjectNode().put("page_size", 100);
    var filter = body.putObject("filter").put("property", "object").put("value", "page");
    if (phase == SearchPhase.TRASH) filter.put("in_trash", true);
    body.putObject("sort").put("direction", "descending").put("timestamp", "last_edited_time");
    var fault =
        NotionFixtures.JSON.createObjectNode().put("method", "POST").put("path", "/v1/search");
    fault.set("body", body);
    var replies = fault.putArray("responses");
    for (JsonNode response : responses)
      replies
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", response.toString())
          .putObject("headers");
    data.server.fault(fault);
  }

  void collect() {
    var client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    new NotionReconciliation(
            NotionRefreshScenario.ROOTS,
            state,
            new NotionReconciliation.Dependencies(
                client, data.input, data.clock, data.server.token()))
        .collect(this::commit);
  }

  private void commit(CollectionPage page) {
    if (page.mutations().stream().anyMatch(m -> m.externalId().equals("block:two")))
      changedBodyWasCompleteBeforeFullContinued =
          Optional.ofNullable(
                  data.input
                      .state()
                      .bodies(data.fingerprint(), List.of(CHANGED_PAGE), data.input.cancel())
                      .get(CHANGED_PAGE))
              .map(summary -> summary.isCompleted(changedVersion()))
              .orElse(false);
    data.commit(page);
    if (advanceAt.test(page)) {
      advanceAt = ignored -> false;
      data.clock.advance(ELAPSED_UNTIL_DUE);
    }
  }

  @Override
  public void close() {
    data.close();
  }
}
