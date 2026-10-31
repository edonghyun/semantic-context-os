package io.scos.connector.notion.internal;

import io.scos.engine.support.call.CursorTrail;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.RawMutation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 루트 모드와 검색 모드가 같이 쓰는 읽기 한 걸음: 검색 한 페이지(원본 변경과 결과 페이지 id), 블록 자식 한 페이지, data source 행 한 페이지. 모드마다
 * 다른 것은 그 뒤의 규칙(자손 작업, 블록 대기열)뿐이다.
 *
 * <p>목록 걷기(검색 갈래 하나, 블록 하나의 자식, data source 하나의 행)마다 이미 요청한 커서를 {@link CursorTrail}로 기억한다 — 다음 커서가
 * 그 가운데 하나로 돌아오면(A → B → A) 응답 위반이다. 이어지는 페이지는 작업 대기열을 거쳐 오므로 자취는 "다음 커서"로 찾는다(같은 블록을 두 번 걸어도 걷기마다
 * 따로다). 체크포인트에서 재개한 걷기는 그 커서부터 새 자취다.
 */
final class NotionReads {
  /** 검색 한 페이지: 원본 변경, 결과 페이지 id(응답 순서), 다음 커서. */
  record Searched(List<RawMutation> mutations, List<String> pageIds, Optional<String> nextCursor) {}

  private final NotionApiClient client;
  private final NotionCalls calls;
  private final NotionMutations mutations;
  private final String token;
  private final Map<Walk, CursorTrail> trails = new HashMap<>();

  /** 걷기 하나의 다음 페이지: 무엇을 걷는가와 그 페이지의 커서. */
  private record Walk(String list, String cursor) {}

  NotionReads(NotionApiClient client, NotionCalls calls, NotionMutations mutations, String token) {
    this.client = client;
    this.calls = calls;
    this.mutations = mutations;
    this.token = token;
  }

  /** 검색은 실패하면 run을 끝낸다. */
  Searched search(Optional<String> cursor, SearchPhase phase) {
    NotionApiClient.ListPage result =
        calls.required(
            NotionApiClient.SEARCH_OPERATION,
            advancing(
                "search:" + phase.wire(),
                cursor,
                timeout -> client.searchPages(token, cursor, phase, timeout)));
    List<RawMutation> changed = mutations.searchResults(result.results(), phase);
    return new Searched(
        changed,
        result.results().stream().map(p -> p.get("id").asString()).toList(),
        result.nextCursor());
  }

  NotionApiClient.ListPage candidates(Optional<String> cursor, SearchPhase phase) {
    return calls.paged(
        NotionApiClient.SEARCH_OPERATION,
        cursor,
        advancing(
            "search:" + phase,
            cursor,
            timeout -> client.searchPages(token, cursor, phase, timeout)));
  }

  /** 블록 자식 한 페이지. object_not_found면 그 작업의 거부다. */
  NotionCalls.Outcome<NotionApiClient.ListPage> blockChildren(NotionWork.BlockChildren work) {
    return calls.isolated(
        work,
        advancing(
            "blocks:" + work.blockId(),
            work.cursor(),
            timeout -> client.listBlockChildren(token, work.blockId(), work.cursor(), timeout)));
  }

  /** data source 행 한 페이지. object_not_found면 그 작업의 거부다. */
  NotionCalls.Outcome<NotionApiClient.ListPage> dataSourceRows(NotionWork.DataSourceRows work) {
    return calls.isolated(
        work,
        advancing(
            "rows:" + work.dataSourceId() + ":" + work.phase().wire(),
            work.cursor(),
            timeout -> client.queryDataSource(token, work.dataSourceId(), work.cursor(), timeout)));
  }

  NotionCalls.Outcome<NotionApiClient.RowResult> rows(
      String id, Optional<String> cursor, NotionRowQuery query) {
    return calls.isolated(
        new NotionWork.DataSourceRows(id, SearchPhase.ACTIVE, cursor),
        cursor,
        advancing(
            "windowed-rows:" + id + ":" + query,
            cursor,
            timeout -> client.queryWindow(token, id, cursor, query, timeout),
            result -> result.page().nextCursor()));
  }

  /**
   * 목록 한 페이지 호출에 커서 자취를 씌운다: 이 커서로 요청했음을 남기고, 받은 다음 커서가 그 걷기에서 이미 요청한 커서면 호출 안에서 위반으로 던진다(다른 응답 위반과
   * 같이 분류된다 — 메시지 끝에 걷기 설명). 재시도는 같은 요청이라 자취를 바꾸지 않는다.
   */
  private CallExecutor.Call<NotionApiClient.ListPage> advancing(
      String list, Optional<String> cursor, CallExecutor.Call<NotionApiClient.ListPage> call) {
    return advancing(list, cursor, call, NotionApiClient.ListPage::nextCursor);
  }

  private <T> CallExecutor.Call<T> advancing(
      String list,
      Optional<String> cursor,
      CallExecutor.Call<T> call,
      Function<T, Optional<String>> nextCursor) {
    // 받은 적 있는 다음 커서면 그 걷기를 잇고, 처음 페이지(커서 없음)나 재개한 커서면 새 걷기다(Optional.map은 null을 empty로)
    CursorTrail walk =
        cursor.map(c -> trails.remove(new Walk(list, c))).orElseGet(CursorTrail::new);
    walk.request(cursor);
    return timeout -> {
      T page = call.invoke(timeout);
      Optional<String> next = nextCursor.apply(page);
      if (walk.revisits(next)) throw NotionApiException.cursorCycle(walk.cycle(list, next));
      next.ifPresent(value -> trails.put(new Walk(list, value), walk));
      return page;
    };
  }
}
