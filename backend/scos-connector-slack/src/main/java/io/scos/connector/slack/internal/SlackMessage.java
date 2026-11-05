package io.scos.connector.slack.internal;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** history·replies가 준 메시지 하나. ts는 객체이고 유효한 문자열일 때만 있다. */
record SlackMessage(JsonNode node, Optional<SlackTs> ts) {
  static final String RESOURCE_TYPE = "slack.message";

  static RawAcl aclForChannel(SourceInstanceId source, String channel, Instant at) {
    return RawAcl.observed(
        DocumentAcl.Status.OK, Set.of(new AccessKey(AccessKey.Kind.GROUP, source, channel)), at);
  }

  static SlackMessage parse(ObjectMapper json, String raw) {
    JsonNode node = json.readTree(raw);
    Optional<SlackTs> ts =
        node.isObject() ? field(node, "ts").flatMap(SlackTs::parse) : Optional.empty();
    return new SlackMessage(node, ts);
  }

  /** 요청한 스레드의 루트이거나 그 스레드를 가리키는 답글이다. */
  boolean belongsTo(SlackTs thread) {
    return ts.get().equals(thread) || thread.text().equals(field(node, "thread_ts").orElse(null));
  }

  /** 답글이 달린 스레드 루트다(reply_count가 양의 정수). */
  boolean startsThread() {
    JsonNode replyCount = node.get("reply_count");
    return replyCount != null && replyCount.isIntegralNumber() && replyCount.asLong() > 0;
  }

  /**
   * 키 channelId:ts, sourceVersion = edited.ts ?? ts, metadata{channelId, messageTs, threadTs,
   * authorId, subtype}.
   */
  RawItem toRawItem(
      ObjectMapper json,
      SourceInstanceId source,
      String channelId,
      Instant observedAt,
      RawContent content) {
    SlackTs messageTs = ts.orElseThrow();
    SlackTs sourceVersion = editedTs().orElse(messageTs);
    ObjectNode metadata = json.createObjectNode();
    metadata.put("channelId", channelId);
    metadata.put("messageTs", messageTs.text());
    metadata.put("threadTs", field(node, "thread_ts").orElse(null));
    metadata.put("authorId", field(node, "user").orElse(null));
    metadata.put("subtype", field(node, "subtype").orElse(null));
    return new RawItem(
        channelId + ":" + messageTs.text(),
        RESOURCE_TYPE,
        sourceVersion.text(),
        Optional.of(sourceVersion.order()),
        Optional.empty(),
        Optional.empty(),
        observedAt,
        json.writeValueAsString(metadata),
        Optional.of(content),
        aclForChannel(source, channelId, observedAt));
  }

  private Optional<SlackTs> editedTs() {
    return Optional.ofNullable(node.get("edited"))
        .filter(JsonNode::isObject)
        .flatMap(e -> field(e, "ts"))
        .flatMap(SlackTs::parse);
  }

  private static Optional<String> field(JsonNode node, String name) {
    JsonNode v = node.get(name);
    return v != null && v.isString() ? Optional.of(v.asString()) : Optional.empty();
  }
}
