package io.scos.connector.notion.internal;

import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CollectionPage;
import java.net.HttpURLConnection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/** 같은 스레드의 커밋 경계에서 실제 증분 본문 수집을 중첩한다. */
final class NotionReentrantRefreshScenario implements AutoCloseable {
  final NotionRefreshScenario data = new NotionRefreshScenario();
  NotionPageRefresh refresh;
  NotionApiClient client;
  NotionCalls calls;
  static final String CHANGING_PAGE = "changing-child";
  static final String MOVING_PARENT = "moving-parent";
  static final String FINAL_VERSION = "2026-10-25T00:02:00Z";
  String targetPage = NotionRefreshScenario.PAGE;
  NotionPageRefresh.Result fullResult;
  NotionPageRefresh.Result incrementalResult;

  enum Boundary {
    BODY_PAGE,
    NEW_VERSION,
    COMPLETION,
    NEW_VERSION_LAST_PAGE
  }

  void interruptAt(Boundary boundary) {
    switch (boundary) {
      case BODY_PAGE -> pagedBodyInterruptedByIncremental();
      case NEW_VERSION -> pageChangesAtIncrementalBoundary();
      case COMPLETION -> incrementalAtBodyCompletion();
      case NEW_VERSION_LAST_PAGE -> pageChangesAtLastBodyBoundary();
    }
  }

  Predicate<CollectionPage> interruptAt = page -> false;

  void pagedBodyInterruptedByIncremental() {
    data.pagedBodyAvailable();
    interruptAt =
        page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
  }

  void pageChangesAtIncrementalBoundary() {
    sequence(
        "/v1/pages/" + NotionRefreshScenario.PAGE,
        data.page(),
        data.page().put("last_edited_time", NotionRefreshScenario.LATEST_VERSION));
    sequence(
        "/v1/blocks/" + NotionRefreshScenario.PAGE + "/children",
        body(Optional.of("body-2"), data.block("one")),
        body(Optional.empty(), data.block("kept")));
    interruptAt =
        page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
  }

  void pageChangesAtLastBodyBoundary() {
    sequence(
        "/v1/pages/" + NotionRefreshScenario.PAGE,
        data.page(),
        data.page().put("last_edited_time", NotionRefreshScenario.LATEST_VERSION));
    sequence(
        "/v1/blocks/" + NotionRefreshScenario.PAGE + "/children",
        body(Optional.empty(), data.block("one")),
        body(Optional.empty(), data.block("kept")));
    interruptAt =
        page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
  }

  void rejectedTailAtIncrementalBoundary() {
    data.bodyPage(
        NotionRefreshScenario.PAGE, Optional.empty(), Optional.of("body-2"), data.block("one"));
    interruptAt =
        page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/blocks/" + NotionRefreshScenario.PAGE + "/children");
    fault.putObject("params").put("page_size", "100").put("start_cursor", "body-2");
    fault
        .putArray("responses")
        .addObject()
        .put("status", HttpURLConnection.HTTP_NOT_FOUND)
        .put("text", "{\"object\":\"error\",\"code\":\"object_not_found\"}")
        .putObject("headers");
    data.server.fault(fault);
  }

  void pageChangesAcrossSuccessorChain() {
    targetPage = CHANGING_PAGE;
    var first = NotionScopeScenario.page(CHANGING_PAGE, "page_id", NotionRefreshScenario.PAGE);
    var second =
        NotionScopeScenario.page(CHANGING_PAGE, "page_id", MOVING_PARENT)
            .put("last_edited_time", NotionRefreshScenario.LATEST_VERSION);
    var latest =
        NotionScopeScenario.page(CHANGING_PAGE, "page_id", NotionRefreshScenario.PAGE)
            .put("last_edited_time", FINAL_VERSION);
    sequence("/v1/pages/" + CHANGING_PAGE, first, second, latest, latest, latest);
    sequence(
        "/v1/pages/" + MOVING_PARENT,
        NotionScopeScenario.page(MOVING_PARENT, "page_id", NotionRefreshScenario.PAGE),
        NotionScopeScenario.page(MOVING_PARENT, "workspace", ""));
    sequence(
        "/v1/blocks/" + CHANGING_PAGE + "/children",
        body(Optional.empty(), data.block("one")),
        body(Optional.empty(), data.block("second")),
        body(Optional.empty(), data.block("latest")));
    interruptAt =
        page -> page.mutations().stream().anyMatch(m -> m.externalId().equals("block:one"));
  }

  private JsonNode body(Optional<String> next, JsonNode block) {
    var response =
        NotionFixtures.JSON
            .createObjectNode()
            .put("object", "list")
            .put("type", "block")
            .put("has_more", next.isPresent());
    if (next.isPresent()) response.put("next_cursor", next.get());
    else response.putNull("next_cursor");
    response.putArray("results").add(block);
    return response;
  }

  private void sequence(String path, JsonNode... responses) {
    var fault = NotionFixtures.JSON.createObjectNode().put("method", "GET").put("path", path);
    var replies = fault.putArray("responses");
    for (JsonNode response : responses)
      replies
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", response.toString())
          .putObject("headers");
    data.server.fault(fault);
  }

  void incrementalAtBodyCompletion() {
    data.emptyBodyAvailable();
    interruptAt =
        page ->
            page.bodies().stream()
                .anyMatch(body -> body.outcome() == BodyObservation.Outcome.COMPLETE);
  }

  long completionFrames() {
    return data.committed.stream()
        .flatMap(page -> page.bodies().stream())
        .filter(body -> body.outcome() == BodyObservation.Outcome.COMPLETE)
        .count();
  }

  void collectFull() {
    client = NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
    calls = new NotionCalls(data.input, data.clock);
    refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client,
                calls,
                new NotionMutations(data.input.content(), data.clock),
                data.input,
                data.state,
                this::commit,
                data.server.token()));
    fullResult = refresh.refresh(targetPage, NotionPageRefresh.Path.FULL, scope());
  }

  private NotionScope scope() {
    return new NotionScope(NotionRefreshScenario.ROOTS, client, calls, data.server.token());
  }

  private void commit(CollectionPage page) {
    data.commit(page);
    if (interruptAt.test(page)) {
      interruptAt = ignored -> false;
      incrementalResult = refresh.refresh(targetPage, NotionPageRefresh.Path.INCREMENTAL, scope());
    }
  }

  Optional<String> fullVersion() {
    return completedVersion(fullResult);
  }

  Optional<String> incrementalVersion() {
    return completedVersion(incrementalResult);
  }

  private Optional<String> completedVersion(NotionPageRefresh.Result result) {
    return result instanceof NotionPageRefresh.Completed completed
        ? Optional.of(completed.version())
        : Optional.empty();
  }

  Optional<NotionPageRefresh.Incompletion> fullIncompletion() {
    return incompletion(fullResult);
  }

  Optional<NotionPageRefresh.Incompletion> incrementalIncompletion() {
    return incompletion(incrementalResult);
  }

  private Optional<NotionPageRefresh.Incompletion> incompletion(NotionPageRefresh.Result result) {
    return result instanceof NotionPageRefresh.Pending pending
        ? Optional.of(pending.reason())
        : Optional.empty();
  }

  long bodyRequests() {
    return data.bodyRequests();
  }

  Set<String> completedBlocks() {
    return data.completedBlocks();
  }

  @Override
  public void close() {
    data.close();
  }
}
