package io.scos.connector.notion.internal;

import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.ReconciliationObservation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 검증된 DB/data source의 행과 새 하위 트리를 수집한다. */
final class NotionDataSources {
  record Dependencies(
      NotionApiClient client,
      NotionCalls calls,
      NotionMutations mutations,
      CollectionInput input,
      NotionReconciliationState state,
      PageSink sink,
      String token,
      Clock clock,
      NotionPageRefresh refresh) {}

  private final Dependencies deps;
  private final NotionRows rows;
  private final Set<String> completedPages = new HashSet<>();
  private final Map<String, Boolean> containers = new HashMap<>();

  NotionDataSources(Dependencies deps) {
    this.deps = deps;
    rows =
        new NotionRows(
            new NotionRows.Dependencies(
                new NotionReads(deps.client(), deps.calls(), deps.mutations(), deps.token()),
                deps.state(),
                deps.sink(),
                deps.clock()));
  }

  boolean poll(NotionScope scope) {
    boolean complete = true;
    for (String id : deps.state().databases())
      complete &= collect(new NotionWork.Database(id), NotionPageRefresh.Path.INCREMENTAL, scope);
    for (String id : deps.state().dataSources())
      complete &= collect(new NotionWork.DataSource(id), NotionPageRefresh.Path.INCREMENTAL, scope);
    return complete;
  }

  boolean collectCandidate(NotionWork work, NotionScope scope) {
    var path = NotionPageRefresh.Path.INCREMENTAL;
    if (!(work instanceof NotionWork.Page page)) return collect(work, path, scope);
    if (deps.state().deferred(work, path)) return false;
    if (completedPages.contains(path + ":" + page.pageId())) return true;
    return finishPage(page, path, scope, deps.refresh().refreshCandidate(page.pageId(), scope));
  }

  boolean collect(NotionWork work, NotionPageRefresh.Path path, NotionScope scope) {
    if (path == NotionPageRefresh.Path.INCREMENTAL
        && work instanceof NotionWork.DataSource
        && deps.state().fullOwnsRows(work.id())) return true;
    if (deps.state().deferred(work, path)) return false;
    if (work instanceof NotionWork.Page) return process(work, path, scope);
    String key = path + ":" + work.operation() + ":" + NotionObjects.idKey(work.id());
    if (containers.containsKey(key)) return containers.get(key);
    containers.put(key, false);
    boolean owned = path == NotionPageRefresh.Path.FULL && work instanceof NotionWork.DataSource;
    if (owned) deps.state().beginFullRows(work.id());
    boolean completed;
    try {
      completed = process(work, path, scope);
    } finally {
      if (owned) deps.state().finishFullRows(work.id());
    }
    containers.put(key, completed);
    return completed;
  }

  private boolean process(NotionWork work, NotionPageRefresh.Path path, NotionScope scope) {
    return switch (work) {
      case NotionWork.Database database -> database(database, path, scope);
      case NotionWork.DataSource source -> dataSource(source, path, scope);
      case NotionWork.Page page -> page(page, path, scope);
      default -> throw new NotionProtocolException("Unsupported Notion container work");
    };
  }

  private boolean database(
      NotionWork.Database database, NotionPageRefresh.Path path, NotionScope scope) {
    var found = metadata(database, path);
    if (found.isEmpty()) return false;
    var value = found.get();
    if (!value.path("id").asString().equals(database.databaseId()))
      throw new NotionProtocolException(NotionRootCollection.WRONG_DATABASE);
    var verdict = scope.check(value);
    if (verdict == NotionScope.Verdict.UNKNOWN) return false;
    if (verdict == NotionScope.Verdict.OUTSIDE) return true;
    var ids = new ArrayList<String>();
    if (!value.path("in_trash").asBoolean())
      value.path("data_sources").forEach(ds -> ids.add(ds.path("id").asString()));
    deps.state().synchronizeDatabase(database.databaseId(), ids, deps.clock().instant());
    emit(deps.mutations().container(NotionMutations.Resource.DATABASE, value), value, path);
    boolean complete = true;
    if (!value.path("in_trash").asBoolean())
      for (var ds : value.path("data_sources"))
        complete &= collect(new NotionWork.DataSource(ds.path("id").asString()), path, scope);
    return complete;
  }

  private boolean page(NotionWork.Page page, NotionPageRefresh.Path path, NotionScope scope) {
    if (completedPages.contains(path + ":" + page.pageId())) return true;
    return finishPage(page, path, scope, deps.refresh().refresh(page.pageId(), path, scope));
  }

  private boolean finishPage(
      NotionWork.Page page,
      NotionPageRefresh.Path path,
      NotionScope scope,
      NotionPageRefresh.Result refreshed) {
    if (refreshed instanceof NotionPageRefresh.Pending pending) {
      if (pending.reason() == NotionPageRefresh.Incompletion.REJECTED)
        deps.state().defer(page, path);
      for (var child : pending.discovered()) collect(child, path, scope);
      return false;
    }
    boolean complete = true;
    for (var child : refreshed.discovered()) complete &= collect(child, path, scope);
    if (!complete) return false;
    completedPages.add(path + ":" + page.pageId());
    deps.state()
        .acknowledgeBody(page.pageId(), ((NotionPageRefresh.Completed) refreshed).version());
    deps.sink().accept(new CollectionPage(List.of(), List.of(), deps.state().checkpoint()));
    return true;
  }

  private boolean dataSource(
      NotionWork.DataSource work, NotionPageRefresh.Path path, NotionScope scope) {
    String id = work.dataSourceId();
    var found = metadata(work, path);
    if (found.isEmpty()) return false;
    var value = found.get();
    if (!value.path("id").asString().equals(id))
      throw new NotionProtocolException(NotionRootCollection.WRONG_DATA_SOURCE);
    var verdict = scope.check(value);
    if (verdict == NotionScope.Verdict.UNKNOWN) return false;
    if (verdict == NotionScope.Verdict.OUTSIDE) {
      deps.state().retireIncrementalRows(id);
      deps.sink().accept(new CollectionPage(List.of(), List.of(), deps.state().checkpoint()));
      return true;
    }
    deps.state().rememberDataSource(id, deps.clock().instant());
    boolean trashed = value.path("in_trash").asBoolean();
    if (trashed) deps.state().retireIncrementalRows(id);
    emit(deps.mutations().container(NotionMutations.Resource.DATA_SOURCE, value), value, path);
    if (trashed) return true;
    boolean initial = deps.state().rowWatermark(id).isEmpty();
    boolean complete = rows.collect(id, path, child -> collect(child, path, scope));
    if (initial && complete)
      complete =
          rows.collect(
              id,
              NotionPageRefresh.Path.INCREMENTAL,
              child -> collect(child, NotionPageRefresh.Path.INCREMENTAL, scope));
    return complete;
  }

  private Optional<JsonNode> metadata(NotionWork work, NotionPageRefresh.Path path) {
    return deps.calls()
        .isolated(
            work,
            timeout ->
                switch (work) {
                  case NotionWork.Database database ->
                      deps.client().retrieveDatabase(deps.token(), database.id(), timeout);
                  case NotionWork.DataSource source ->
                      deps.client().retrieveDataSource(deps.token(), source.id(), timeout);
                  default ->
                      throw new NotionProtocolException("Unsupported Notion container metadata");
                })
        .fold(
            rejected -> {
              deps.state().retireContainer(work);
              deps.state().defer(work, path);
              deps.sink()
                  .accept(
                      new CollectionPage(List.of(), List.of(rejected), deps.state().checkpoint()));
              return Optional.empty();
            },
            Optional::of);
  }

  private void emit(RawMutation mutation, JsonNode value, NotionPageRefresh.Path path) {
    var read =
        new ReconciliationObservation(
            deps.state().fingerprint(),
            mutation.externalId(),
            ReconciliationKind.CONTAINER,
            deps.state().reconciliation(value, path));
    deps.sink()
        .accept(
            new CollectionPage(
                List.of(mutation),
                List.of(),
                deps.state().checkpoint(),
                List.of(),
                Optional.empty(),
                List.of(read)));
  }
}
