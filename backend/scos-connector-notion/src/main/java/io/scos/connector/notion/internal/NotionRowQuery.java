package io.scos.connector.notion.internal;

import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 행 변경 탐색의 고정 하한. HTTP 요청 표현은 이 경계에서만 만든다. */
record NotionRowQuery(Optional<Instant> lowerBound, Optional<Instant> createdFrom) {
  static NotionRowQuery all() {
    return new NotionRowQuery(Optional.empty(), Optional.empty());
  }

  static NotionRowQuery editedSince(Instant lowerBound) {
    return new NotionRowQuery(Optional.of(lowerBound), Optional.empty());
  }

  NotionRowQuery createdOnOrAfter(Instant created) {
    return new NotionRowQuery(lowerBound, Optional.of(created));
  }

  void applyTo(ObjectNode body) {
    lowerBound.ifPresent(
        lower -> {
          ObjectNode filter =
              JsonNodeFactory.instance.objectNode().put("timestamp", "last_edited_time");
          filter.putObject("last_edited_time").put("on_or_after", lower.toString());
          body.set("filter", filter);
        });
    createdFrom.ifPresent(
        created -> {
          var filter = JsonNodeFactory.instance.objectNode().put("timestamp", "created_time");
          filter.putObject("created_time").put("on_or_after", created.toString());
          if (body.has("filter")) {
            var all = JsonNodeFactory.instance.objectNode();
            all.putArray("and").add(body.get("filter")).add(filter);
            body.set("filter", all);
          } else body.set("filter", filter);
        });
    body.putArray("sorts")
        .addObject()
        .put("timestamp", "created_time")
        .put("direction", "ascending");
  }
}
