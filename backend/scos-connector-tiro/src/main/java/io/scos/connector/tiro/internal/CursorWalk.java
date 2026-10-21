package io.scos.connector.tiro.internal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 커서 페이지네이션 한 번의 진행(TS tiro-adapter.ts의 seen 집합). 받은 다음 커서가 이미 요청한 커서면 그 페이지를 쓰기 전에 위반이다(TS와 같은
 * 시점). 같은 커서(첫 페이지는 빈 커서)로 두 번 요청하는 일도 위반으로 막는다 — 정상 흐름에서는 일어나지 않고, 루프가 잘못되면 끝없이 돌지 않고 곧바로 실패하게 한다.
 */
public final class CursorWalk {
  private static final String FIRST_PAGE = "";

  private final Set<String> requested = new HashSet<>();
  private final int pageSize;
  private final String stuck;

  public CursorWalk(int pageSize, String stuck) {
    this.pageSize = pageSize;
    this.stuck = stuck;
  }

  /** 이 커서로 보낼 요청의 쿼리(`size`, 있으면 `cursor` — TS URLSearchParams 순서). */
  public Map<String, String> query(Optional<String> cursor) {
    if (!requested.add(cursor.orElse(FIRST_PAGE))) throw TiroResponses.invalid(stuck);
    Map<String, String> query = new LinkedHashMap<>();
    query.put("size", Integer.toString(pageSize));
    cursor.ifPresent(c -> query.put("cursor", c));
    return query;
  }

  /** 받은 다음 커서가 이미 요청한 커서면 위반. */
  public void received(Optional<String> next) {
    if (next.filter(requested::contains).isPresent()) throw TiroResponses.invalid(stuck);
  }
}
