package io.scos.connector.tiro.internal;

import io.scos.engine.support.call.CursorTrail;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 커서 페이지네이션 한 번의 진행({@link CursorTrail}). 받은 다음 커서가 이미 요청한 커서면 그 페이지를 쓰기 전에 위반이다(메시지에 걷기 설명). 같은
 * 커서(첫 페이지는 빈 커서)로 두 번 요청하는 일도 위반으로 막는다 — 정상 흐름에서는 일어나지 않고, 루프가 잘못되면 끝없이 돌지 않고 곧바로 실패하게 한다.
 */
public final class CursorWalk {
  private final CursorTrail trail = new CursorTrail();
  private final String list;
  private final int pageSize;
  private final String stuck;

  /** list: 걷는 목록(요청 경로) — 위반 메시지의 걷기 설명에 싣는다. */
  public CursorWalk(String list, int pageSize, String stuck) {
    this.list = list;
    this.pageSize = pageSize;
    this.stuck = stuck;
  }

  /** 이 커서로 보낼 요청의 쿼리(`size`, 있으면 `cursor` — 이 순서로). */
  public Map<String, String> query(Optional<String> cursor) {
    if (trail.revisits(Optional.of(cursor.orElse("")))) throw stuck(cursor);
    trail.request(cursor);
    Map<String, String> query = new LinkedHashMap<>();
    query.put("size", Integer.toString(pageSize));
    cursor.ifPresent(c -> query.put("cursor", c));
    return query;
  }

  /** 받은 다음 커서가 이미 요청한 커서면 위반. */
  public void received(Optional<String> next) {
    if (trail.revisits(next)) throw stuck(next);
  }

  /** 위반: 메시지 끝에 걷기 설명(목록·커서 지문)을 괄호로. */
  private TiroProtocolException stuck(Optional<String> cursor) {
    return TiroProtocolException.invalid(stuck + " (" + trail.cycle(list, cursor) + ")");
  }
}
