package io.scos.mcp;

import io.scos.mcp.wire.Wire;
import java.util.List;

/** 검색 질의 기록(TS QueryLogWriter): search_context가 응답을 만든 뒤 한 번 부른다. 꺼져 있으면 {@link #NONE}. */
public interface QueryLog {
  QueryLog NONE = (query, hits) -> {};

  void append(String query, List<Wire.SearchHit> hits);
}
