package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionSearchState.BlockParent;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 검색 모드 run 하나(TS NotionAdapter.collect의 search 갈래): 블록 대기열이 있으면 그 첫 부모의 자식 한 페이지, 없으면 검색 한 페이지가
 * 페이지 하나다. active 검색의 페이지는 본문 대기열이 되고, 자식이 있는 블록은 대기열 끝에, 다음 커서는 그 부모를 맨 앞에 남긴다. 휴지통 단계의 마지막 검색
 * 페이지로 run이 끝난다(다음 run은 active부터). 블록 목록의 object_not_found는 그 부모의 거부다(검색 모드에는 루트가 없다).
 */
public final class NotionSearchCollection {
  private final NotionApiClient client;
  private final NotionCalls calls;
  private final NotionMutations mutations;
  private final String token;
  private NotionSearchState state;

  public NotionSearchCollection(
      NotionSearchState state,
      NotionApiClient client,
      NotionCalls calls,
      NotionMutations mutations,
      String token) {
    this.state = state;
    this.client = client;
    this.calls = calls;
    this.mutations = mutations;
    this.token = token;
  }

  public void collect(PageSink sink) {
    while (true) {
      if (!state.pending().isEmpty()) {
        sink.accept(blockPage());
        continue;
      }
      SearchPhase phase = state.phase();
      NotionApiClient.ListPage result =
          calls.required(
              NotionApiClient.SEARCH_OPERATION,
              timeout -> client.searchPages(token, state.searchCursor(), phase, timeout));
      List<RawMutation> changed = mutations.searchResults(result.results(), phase);
      state =
          state.afterSearch(
              result.nextCursor(),
              result.results().stream().map(p -> p.get("id").asString()).toList());
      sink.accept(new CollectionPage(changed, List.of(), state.checkpoint()));
      if (phase == SearchPhase.TRASH && result.nextCursor().isEmpty() && state.pending().isEmpty())
        return;
    }
  }

  private CollectionPage blockPage() {
    BlockParent current = state.pending().getFirst();
    List<BlockParent> remaining = state.pending().subList(1, state.pending().size());
    return calls
        .isolated(
            new NotionWork.BlockChildren(current.blockId(), current.cursor()),
            timeout ->
                client.listBlockChildren(token, current.blockId(), current.cursor(), timeout))
        .fold(
            rejection -> rejected(remaining, rejection),
            result -> listed(current, remaining, result));
  }

  private CollectionPage rejected(List<BlockParent> remaining, RejectedItem rejection) {
    state = state.afterBlocks(remaining);
    return new CollectionPage(List.of(), List.of(rejection), state.checkpoint());
  }

  private CollectionPage listed(
      BlockParent current, List<BlockParent> remaining, NotionApiClient.ListPage result) {
    List<RawMutation> changed = new ArrayList<>();
    List<BlockParent> nested = new ArrayList<>();
    for (JsonNode block : result.results()) {
      changed.add(mutations.block(current.blockId(), block));
      if (block.get("has_children").asBoolean())
        nested.add(new BlockParent(block.get("id").asString(), Optional.empty()));
    }
    List<BlockParent> next = new ArrayList<>();
    result
        .nextCursor()
        .ifPresent(cursor -> next.add(new BlockParent(current.blockId(), Optional.of(cursor))));
    next.addAll(remaining);
    next.addAll(nested);
    state = state.afterBlocks(next);
    return new CollectionPage(changed, List.of(), state.checkpoint());
  }
}
