package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.UNKNOWN_PAGE;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunOutcome;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SnapshotScope;
import io.scos.support.FakeHttpServer.Response;
import io.scos.support.TestMetrics;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** 가짜 HTTP와 공유 계약을 통과한 저장소 위에서 실제 검증·최종 정리를 연결한다. */
final class NotionAbsenceScenario implements AutoCloseable {
  static final String UNKNOWN_DATABASE = "unknown-parent";
  static final String CANDIDATE = "missing-from-full";
  static final String RETURNING_PAGE = "a-returning";
  static final int MAX_SAFE_RECOVERIES = 3;
  final NotionRefreshScenario data = new NotionRefreshScenario();
  final SnapshotScope scope =
      new SnapshotScope(
          data.fingerprint(), Set.of("notion.page", "notion.database", "notion.data_source"));
  final RunCompletion completion =
      new RunCompletion(
          data.stores,
          new SnapshotShrinkGuard(
              new MemorySourceInstanceStore(),
              new MemorySnapshotGuardStore(),
              SnapshotShrinkLimits.DEFAULTS,
              TestMetrics.collection()));
  final CollectionInput input =
      new CollectionInput(
          data.input.source(),
          data.input.checkpoint(),
          data.input.mode(),
          data.input.calls(),
          data.input.content(),
          data.input.cancel(),
          completion.state(NotionAssembly.SOURCE, NotionRefreshScenario.RUN, Optional.of(scope)));
  final NotionApiClient client =
      NotionAssembly.client(data.server.baseUrl(), NotionClientScenario.TIMEOUT);
  final NotionCalls calls = new NotionCalls(input, data.clock);
  final NotionMutations mutations = new NotionMutations(input.content(), data.clock);
  private String candidateId = CANDIDATE;
  private Consumer<CollectionPage> afterCommit = page -> {};
  private int recoveryCount;

  NotionAbsenceScenario() {
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(data.page()));
    data.runs.observe(NotionRefreshScenario.RUN, List.of(NotionRefreshScenario.PAGE));
    data.runs.requireAbsenceVerification(NotionRefreshScenario.RUN, scope.fingerprint());
  }

  void unknownChildAppearsDuringRecovery() {
    var page = NotionScopeScenario.page(CANDIDATE, "page_id", NotionRefreshScenario.PAGE);
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(page));
    data.server.respond(
        "GET",
        "/v1/pages/" + CANDIDATE,
        new Response(HttpURLConnection.HTTP_OK, page.toString(), Map.of()));
    var child = data.block("pending-child").put("type", "child_page");
    child.remove("paragraph");
    child.putObject("child_page").put("title", "Pending child");
    data.bodyPage(CANDIDATE, Optional.empty(), Optional.empty(), child);
    var unknown = NotionScopeScenario.page("pending-child", "unknown_parent", UNKNOWN_PAGE);
    data.server.respond(
        "GET",
        "/v1/pages/pending-child",
        new Response(HttpURLConnection.HTTP_OK, unknown.toString(), Map.of()));
    var outside = NotionScopeScenario.page(RETURNING_PAGE, "workspace", "");
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(outside));
    data.server.respond(
        "GET",
        "/v1/pages/" + RETURNING_PAGE,
        new Response(HttpURLConnection.HTTP_OK, outside.toString(), Map.of()));
  }

  enum SearchCandidateHistory {
    NEVER_SEEN,
    DELETED
  }

  void unverifiedSearchRejectionPrecedesCleanup(SearchCandidateHistory history) {
    pageMovedOutside();
    if (history == SearchCandidateHistory.DELETED) {
      rememberActiveSearchCandidate();
      var trashed =
          NotionScopeScenario.page(
                  NotionIncrementalScenario.REJECTED_PAGE, "page_id", NotionRefreshScenario.PAGE)
              .put("in_trash", true)
              .put("last_edited_time", NotionRefreshScenario.LATEST_VERSION);
      data.raw.apply(NotionAssembly.SOURCE, mutations.page(trashed));
    }
    collectRejectedSearchCandidate();
  }

  void knownActiveSearchRejectionPrecedesCleanup() {
    pageMovedOutside();
    rememberActiveSearchCandidate();
    collectRejectedSearchCandidate();
  }

  private void rememberActiveSearchCandidate() {
    var page =
        NotionScopeScenario.page(
            NotionIncrementalScenario.REJECTED_PAGE, "page_id", NotionRefreshScenario.PAGE);
    var mutation = (RawMutation.Upsert) mutations.page(page);
    data.raw.apply(NotionAssembly.SOURCE, mutation);
    data.bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            NotionIncrementalScenario.REJECTED_PAGE,
            mutation.item().sourceVersion(),
            mutation.item().sourceOrder().orElseThrow(),
            data.clock.instant(),
            Set.of()));
  }

  private void collectRejectedSearchCandidate() {
    var incremental = new NotionIncrementalScenario(data);
    incremental.rejectedCandidateBeforeRoot();
    incremental.collect();
  }

  void unrelatedCandidateWasDeferred() {
    pageMovedOutside();
    var unknown =
        NotionScopeScenario.object("database", UNKNOWN_DATABASE, "future_parent", UNKNOWN_PAGE);
    unknown.putArray("data_sources");
    data.server.respond(
        "GET",
        "/v1/databases/" + UNKNOWN_DATABASE,
        new Response(HttpURLConnection.HTTP_OK, unknown.toString(), Map.of()));
    new NotionDataSources(dataSources())
        .collect(
            new NotionWork.Database(UNKNOWN_DATABASE),
            NotionPageRefresh.Path.INCREMENTAL,
            new NotionScope(data.state, client, calls, data.server.token()));
  }

  void activePageIsInside() {
    var page = NotionScopeScenario.page(CANDIDATE, "page_id", NotionRefreshScenario.PAGE);
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(page));
    data.server.respond(
        "GET",
        "/v1/pages/" + CANDIDATE,
        new Response(HttpURLConnection.HTTP_OK, page.toString(), Map.of()));
    data.bodyPage(CANDIDATE, Optional.empty(), Optional.empty());
  }

  void pageMovedOutside() {
    activePageIsInside();
    data.server.respond(
        "GET",
        "/v1/pages/" + CANDIDATE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page(CANDIDATE, "workspace", "").toString(),
            Map.of()));
  }

  void wrongPageIsReturned() {
    activePageIsInside();
    data.server.respond(
        "GET",
        "/v1/pages/" + CANDIDATE,
        new Response(
            HttpURLConnection.HTTP_OK,
            NotionScopeScenario.page("unrelated-page", "workspace", "").toString(),
            Map.of()));
  }

  void knownPageIsTrashedWithUnknownParent() {
    activePageIsInside();
    var stored = (RawRecord.Active) data.raw.get(NotionAssembly.SOURCE, CANDIDATE).orElseThrow();
    data.bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            CANDIDATE,
            stored.item().sourceVersion(),
            stored.item().sourceOrder().orElseThrow(),
            data.clock.instant(),
            Set.of()));
    var trashed =
        NotionScopeScenario.page(CANDIDATE, "future_parent", UNKNOWN_PAGE)
            .put("in_trash", true)
            .put("last_edited_time", NotionRefreshScenario.LATEST_VERSION);
    data.server.respond(
        "GET",
        "/v1/pages/" + CANDIDATE,
        new Response(HttpURLConnection.HTTP_OK, trashed.toString(), Map.of()));
  }

  void activeDatabaseIsInside() {
    var database =
        NotionScopeScenario.object("database", CANDIDATE, "page_id", NotionRefreshScenario.PAGE);
    database.putArray("data_sources");
    candidateId = NotionMutations.Resource.DATABASE.prefix + CANDIDATE;
    data.raw.apply(
        NotionAssembly.SOURCE, mutations.container(NotionMutations.Resource.DATABASE, database));
    data.server.respond(
        "GET",
        "/v1/databases/" + CANDIDATE,
        new Response(HttpURLConnection.HTTP_OK, database.toString(), Map.of()));
  }

  void activeDataSourceIsInside() {
    var inventory = new NotionDataSourcesScenario(data);
    inventory.oldRowInNewDataSource();
    candidateId =
        NotionMutations.Resource.DATA_SOURCE.prefix + NotionDataSourcesScenario.DATA_SOURCE;
    var source =
        NotionScopeScenario.object(
            "data_source",
            NotionDataSourcesScenario.DATA_SOURCE,
            "database_id",
            NotionDataSourcesScenario.DATABASE);
    data.raw.apply(
        NotionAssembly.SOURCE, mutations.container(NotionMutations.Resource.DATA_SOURCE, source));
  }

  void knownContainerIsTrashed(NotionMutations.Resource resource) {
    String id;
    if (resource == NotionMutations.Resource.DATABASE) {
      activeDatabaseIsInside();
      id = CANDIDATE;
      data.state.rememberDatabase(id, data.clock.instant());
    } else {
      activeDataSourceIsInside();
      id = NotionDataSourcesScenario.DATA_SOURCE;
      data.state.rememberDataSource(id, data.clock.instant());
    }
    var value =
        NotionScopeScenario.object(
                resource.name().toLowerCase(Locale.ROOT), id, "future_parent", UNKNOWN_PAGE)
            .put("in_trash", true)
            .put("last_edited_time", NotionRefreshScenario.LATEST_VERSION);
    if (resource == NotionMutations.Resource.DATABASE) value.putArray("data_sources");
    String path =
        resource == NotionMutations.Resource.DATABASE ? "/v1/databases/" : "/v1/data_sources/";
    data.server.respond(
        "GET", path + id, new Response(HttpURLConnection.HTTP_OK, value.toString(), Map.of()));
  }

  void knownContainerIsActiveOutside(NotionMutations.Resource resource) {
    knownContainerIsTrashed(resource);
    String id =
        resource == NotionMutations.Resource.DATABASE
            ? CANDIDATE
            : NotionDataSourcesScenario.DATA_SOURCE;
    var value =
        NotionScopeScenario.object(resource.name().toLowerCase(Locale.ROOT), id, "workspace", "");
    if (resource == NotionMutations.Resource.DATABASE) value.putArray("data_sources");
    String path =
        resource == NotionMutations.Resource.DATABASE ? "/v1/databases/" : "/v1/data_sources/";
    data.server.respond(
        "GET", path + id, new Response(HttpURLConnection.HTTP_OK, value.toString(), Map.of()));
  }

  void blockOwnerMovedOutside() {
    pageMovedOutside();
    rememberOwnedBlock();
  }

  void previouslyTrashedOwnerHasActiveBlock() {
    blockOwnerIsTrashed();
    var trashed =
        NotionScopeScenario.page(CANDIDATE, "future_parent", UNKNOWN_PAGE)
            .put("in_trash", true)
            .put("last_edited_time", NotionRefreshScenario.LATEST_VERSION);
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(trashed));
  }

  void blockOwnerIsTrashed() {
    knownPageIsTrashedWithUnknownParent();
    rememberOwnedBlock();
  }

  private void rememberOwnedBlock() {
    data.raw.apply(NotionAssembly.SOURCE, mutations.block(CANDIDATE, data.block("owned")));
    var stored = (RawRecord.Active) data.raw.get(NotionAssembly.SOURCE, CANDIDATE).orElseThrow();
    data.bodies.save(
        NotionAssembly.SOURCE,
        new BodyCompletionStore.Body(
            scope.fingerprint(),
            CANDIDATE,
            stored.item().sourceVersion(),
            stored.item().sourceOrder().orElseThrow(),
            data.clock.instant(),
            Set.of("block:owned")));
  }

  boolean ownedBlockIsActive() {
    return data.raw.get(NotionAssembly.SOURCE, "block:owned").orElse(null)
        instanceof RawRecord.Active;
  }

  void pageReturnsInsideDuringRecovery() {
    activePageIsInside();
    var inside = NotionScopeScenario.page(RETURNING_PAGE, "page_id", NotionRefreshScenario.PAGE);
    data.raw.apply(NotionAssembly.SOURCE, mutations.page(inside));
    data.bodyPage(RETURNING_PAGE, Optional.empty(), Optional.empty());
    var fault =
        NotionFixtures.JSON
            .createObjectNode()
            .put("method", "GET")
            .put("path", "/v1/pages/" + RETURNING_PAGE);
    var responses = fault.putArray("responses");
    for (var value : List.of(NotionScopeScenario.page(RETURNING_PAGE, "workspace", ""), inside))
      responses
          .addObject()
          .put("status", HttpURLConnection.HTTP_OK)
          .put("text", value.toString())
          .putObject("headers");
    data.server.fault(fault);
  }

  boolean returningPageIsActive() {
    return data.raw.get(NotionAssembly.SOURCE, RETURNING_PAGE).orElse(null)
        instanceof RawRecord.Active;
  }

  void exceedsCandidateLimit() {
    for (int number = 0; number <= SnapshotScope.MAX_CANDIDATES; number++)
      data.raw.apply(
          NotionAssembly.SOURCE,
          mutations.page(
              NotionScopeScenario.page(
                  "unobserved-" + number, "page_id", NotionRefreshScenario.PAGE)));
  }

  long remoteRequestCount() {
    return data.server.requests().size();
  }

  boolean verificationWasStored() {
    return data.runs.absenceValidation(NotionRefreshScenario.RUN).isPresent();
  }

  void candidatesKeepChanging() {
    activePageIsInside();
    afterCommit =
        page -> {
          if (page.bodies().stream()
              .noneMatch(body -> body.outcome() == BodyObservation.Outcome.COMPLETE)) return;
          if (++recoveryCount > MAX_SAFE_RECOVERIES) throw new AssertionError("검증이 복구 예산을 넘었다");
          String id = "churn-" + recoveryCount;
          var discovered = NotionScopeScenario.page(id, "page_id", NotionRefreshScenario.PAGE);
          data.raw.apply(NotionAssembly.SOURCE, mutations.page(discovered));
          data.server.respond(
              "GET",
              "/v1/pages/" + id,
              new Response(HttpURLConnection.HTTP_OK, discovered.toString(), Map.of()));
          data.bodyPage(id, Optional.empty(), Optional.empty());
        };
  }

  private void commit(CollectionPage page) {
    data.commit(page);
    afterCommit.accept(page);
  }

  enum MissingEvidence {
    FORBIDDEN,
    NOT_FOUND,
    UNKNOWN_PARENT
  }

  void unverifiablePage(MissingEvidence reason) {
    activePageIsInside();
    var response =
        switch (reason) {
          case FORBIDDEN ->
              new Response(
                  HttpURLConnection.HTTP_FORBIDDEN,
                  "{\"object\":\"error\",\"code\":\"restricted_resource\",\"message\":\"forbidden\"}",
                  Map.of());
          case NOT_FOUND ->
              new Response(
                  HttpURLConnection.HTTP_NOT_FOUND,
                  "{\"object\":\"error\",\"code\":\"object_not_found\",\"message\":\"not found\"}",
                  Map.of());
          case UNKNOWN_PARENT ->
              new Response(
                  HttpURLConnection.HTTP_OK,
                  NotionScopeScenario.page(CANDIDATE, "future_parent", UNKNOWN_PAGE).toString(),
                  Map.of());
        };
    data.server.respond("GET", "/v1/pages/" + CANDIDATE, response);
  }

  private NotionDataSources.Dependencies dataSources() {
    var refresh =
        new NotionPageRefresh(
            new NotionPageRefresh.Dependencies(
                client, calls, mutations, input, data.state, this::commit, data.server.token()));
    return new NotionDataSources.Dependencies(
        client,
        calls,
        mutations,
        input,
        data.state,
        this::commit,
        data.server.token(),
        data.clock,
        refresh);
  }

  CollectionRun reconcile() {
    completion.recordSnapshotBaseline(NotionAssembly.SOURCE, NotionRefreshScenario.RUN);
    new NotionAbsences(dataSources()).verify();
    return finish();
  }

  boolean bodyCompleted() {
    return data.bodies.find(NotionAssembly.SOURCE, scope.fingerprint(), CANDIDATE).isPresent();
  }

  Optional<String> recoveredDataSourceRowVersion() {
    return data.bodies
        .find(NotionAssembly.SOURCE, scope.fingerprint(), NotionDataSourcesScenario.OLD_ROW)
        .map(BodyCompletionStore.Body::version);
  }

  private CollectionRun finish() {
    return completion.complete(
        new RunCompletion.Input(
            data.claim,
            NotionRefreshScenario.RUN,
            RunOutcome.succeeded(data.runs.get(NotionRefreshScenario.RUN).orElseThrow().rejected()),
            CollectionCoverage.COMPLETE_SNAPSHOT,
            data.clock.instant(),
            AccessResult.SKIPPED,
            Optional.empty(),
            Optional.empty(),
            Optional.of(scope)));
  }

  boolean candidateIsActive() {
    return data.raw.get(NotionAssembly.SOURCE, candidateId).orElse(null)
        instanceof RawRecord.Active;
  }

  @Override
  public void close() {
    data.close();
  }
}
