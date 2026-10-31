package io.scos.connector.tiro.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 목록·문단 한 페이지: 항목과 다음 커서(마지막이면 없음). */
record TiroCursorPage(List<ObjectNode> entries, Optional<String> nextCursor) {
  /** `{content: [...], nextCursor: null | 비어 있지 않은 문자열}` — nextCursor가 없거나 빈 문자열이면 위반. */
  static TiroCursorPage read(JsonNode value) {
    ObjectNode page = TiroJson.object(value);
    JsonNode content = page.get("content");
    JsonNode next = page.get("nextCursor");
    if (content == null
        || !content.isArray()
        || next == null
        || !(next.isNull() || (next.isString() && !next.asString().isEmpty())))
      throw TiroProtocolException.invalid("Invalid Tiro pagination envelope");
    List<ObjectNode> entries = new ArrayList<>();
    for (JsonNode entry : content) entries.add(TiroJson.object(entry));
    return new TiroCursorPage(
        entries, next.isNull() ? Optional.empty() : Optional.of(next.asString()));
  }
}
