package io.scos.mcp;

import io.scos.mcp.wire.Wire;
import io.scos.spi.store.Principal;
import java.util.List;
import java.util.function.UnaryOperator;

/** 요청자별 검색 질의 기록: search_context가 응답을 만든 뒤 한 번 부른다. 꺼져 있으면 {@link #NONE}. */
public interface QueryLog {
  QueryLog NONE = (principal, query, hits) -> {};

  void append(Principal principal, String query, List<Wire.SearchHit> hits);

  /** 질의 텍스트만 {@code mapper}로 바꿔 이 로그에 넘긴다. 요청자와 hits는 그대로다. */
  default QueryLog mapQuery(UnaryOperator<String> mapper) {
    return (principal, query, hits) -> append(principal, mapper.apply(query), hits);
  }
}
