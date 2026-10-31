package io.scos.connector.notion.internal;

import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** roots의 active/trash 탐색과 발견한 작업의 완료를 소유한다. */
final class NotionIncrementalCollection {
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

  // 원격 커서가 반복해서 만료되면 무한 탐색 대신 미확정 상태로 실패한다.
  private static final int MAX_CURSOR_RESTARTS = 3;
  private final Dependencies deps;
  private final NotionReads reads;
  private @Nullable NotionDataSources sources;

  NotionIncrementalCollection(Dependencies deps) {
    this.deps = deps;
    this.reads = new NotionReads(deps.client(), deps.calls(), deps.mutations(), deps.token());
  }

  void collect() {
    Instant startedAt = deps.state().beginIncremental(deps.clock().instant());
    checkpoint();
    sources =
        new NotionDataSources(
            new NotionDataSources.Dependencies(
                deps.client(),
                deps.calls(),
                deps.mutations(),
                deps.input(),
                deps.state(),
                deps.sink(),
                deps.token(),
                deps.clock(),
                deps.refresh()));
    for (SearchPhase phase : SearchPhase.values()) collect(phase);
    if (sources.poll(new NotionScope(deps.state(), deps.client(), deps.calls(), deps.token())))
      deps.state().finishIncremental(startedAt);
    checkpoint();
  }

  private void collect(SearchPhase phase) {
    var scan = deps.state().beginSearch(phase, deps.clock().instant());
    checkpoint();
    var scope = new NotionScope(deps.state(), deps.client(), deps.calls(), deps.token());
    finishWork(phase, scope);
    boolean searchNeeded = !scan.listingComplete();
    Instant lowerBound = scan.lowerBound();
    Instant previousEdited = Instant.MAX;
    int restarts = 0;
    while (searchNeeded) {
      NotionApiClient.ListPage result;
      try {
        result =
            reads.candidates(
                deps.state().beginSearch(phase, deps.clock().instant()).cursor(), phase);
      } catch (NotionCursorExpired expired) {
        if (++restarts > MAX_CURSOR_RESTARTS) throw expired;
        deps.state().restartSearch(phase);
        previousEdited = Instant.MAX;
        checkpoint();
        continue;
      }
      previousEdited = validateEditOrder(result, previousEdited, phase);
      deps.state()
          .searchPage(phase, continuation(result, lowerBound), candidates(result, lowerBound));
      finishWork(phase, scope);
      searchNeeded = deps.state().beginSearch(phase, deps.clock().instant()).cursor().isPresent();
    }
    if (deps.state().beginSearch(phase, deps.clock().instant()).work().isEmpty())
      deps.state().completeSearch(phase, deps.clock().instant());
    checkpoint();
  }

  private Instant validateEditOrder(
      NotionApiClient.ListPage result, Instant previousEdited, SearchPhase phase) {
    for (var page : result.results()) {
      NotionMutations.requireSearchPhase(page, phase);
      Instant edited = NotionMutations.timestamp(page.path("last_edited_time").asString());
      if (edited.isAfter(previousEdited))
        throw new NotionProtocolException("Notion search results are not ordered by edit time");
      previousEdited = edited;
    }
    return previousEdited;
  }

  private Optional<String> continuation(NotionApiClient.ListPage result, Instant lowerBound) {
    boolean boundary =
        result.results().stream()
            .anyMatch(
                page ->
                    NotionMutations.timestamp(page.path("last_edited_time").asString())
                        .isBefore(lowerBound));
    return boundary ? Optional.empty() : result.nextCursor();
  }

  private List<NotionWork> candidates(NotionApiClient.ListPage result, Instant lowerBound) {
    return result.results().stream()
        .filter(
            page ->
                !NotionMutations.timestamp(page.path("last_edited_time").asString())
                    .isBefore(lowerBound))
        .map(
            page -> {
              deps.state().observePage(page, NotionPageRefresh.Path.INCREMENTAL);
              return (NotionWork) new NotionWork.Page(page.path("id").asString());
            })
        .toList();
  }

  private void finishWork(SearchPhase phase, NotionScope scope) {
    for (var work : deps.state().beginSearch(phase, deps.clock().instant()).work()) {
      // collect가 탐색 전에 공급자를 준비한다.
      if (Objects.requireNonNull(sources).collectCandidate(work, scope))
        deps.state().completedSearchWork(phase, work);
      else deps.state().retrySearchWork(phase, work);
      checkpoint();
    }
  }

  private void checkpoint() {
    deps.sink().accept(new CollectionPage(List.of(), List.of(), deps.state().checkpoint()));
  }
}
