package io.scos.client.source.transcript;

import io.scos.client.ClientJson;
import io.scos.client.ContentHash;
import io.scos.client.DocumentMetadata;
import io.scos.client.redact.JsonRedaction;
import io.scos.client.source.Rejections;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RejectedItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** 완전한 트랜스크립트 줄 하나를 가린 원본 또는 원문 없는 거절로 바꾼다. */
final class TranscriptLineCapture {
  private static final String LINE = "claude.transcript.line";
  private final ContentCapture content;
  private final Instant now;
  private final ContentHash hash = new ContentHash();
  private final JsonRedaction redaction = JsonRedaction.standard();
  private final Rejections rejections;

  sealed interface Outcome {}

  record Accepted(RawItem item, long bytes) implements Outcome {}

  record Rejected(RejectedItem item) implements Outcome {}

  TranscriptLineCapture(ContentCapture content, Instant now) {
    this.content = content;
    this.now = now;
    this.rejections = new Rejections(now);
  }

  Outcome capture(JsonlReader.Line line, String documentKey) {
    String externalId = documentKey + "#" + line.number();
    String raw = new String(line.bytes(), StandardCharsets.UTF_8);
    JsonNode node;
    try {
      node = redaction.read(raw);
    } catch (JacksonException e) {
      return reject(RejectedItem.FailureKind.INVALID_ITEM, "파싱할 수 없는 줄", externalId);
    }
    if (node == null || !node.isObject()) {
      return reject(RejectedItem.FailureKind.INVALID_ITEM, "파싱할 수 없는 줄", externalId);
    }
    String redacted = redaction.apply(raw, node);
    RawContent captured = content.json(ClientJson.MEDIA_TYPE, redacted);
    byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
    String timestamp = node.path("timestamp").asString("");
    return new Accepted(
        new RawItem(
            externalId,
            isTurn(node) ? TranscriptAdapter.TURN : LINE,
            hash.sha256(bytes),
            Optional.empty(),
            Optional.empty(),
            timestamp.isEmpty() ? Optional.empty() : Optional.of(timestamp),
            now,
            DocumentMetadata.of("transcript/" + documentKey).toJson(),
            Optional.of(captured),
            RawAcl.notObserved()),
        bytes.length);
  }

  private Rejected reject(RejectedItem.FailureKind kind, String message, String externalId) {
    return new Rejected(rejections.of(kind, message, externalId));
  }

  /** 투영에 쓰는 줄: assistant · ai-title · 사용자 텍스트 · 질문 답변(toolUseResult.answers). */
  private static boolean isTurn(JsonNode node) {
    String type = node.path("type").asString("");
    if (type.equals("assistant") || type.equals("ai-title")) return true;
    if (!type.equals("user")) return false;
    if (!node.path("toolUseResult").path("answers").isMissingNode()
        && !node.path("toolUseResult").path("answers").isNull()) return true;
    JsonNode content = node.path("message").path("content");
    if (content.isString()) return true;
    if (content.isArray()) {
      for (JsonNode block : content) {
        if (block.path("type").asString("").equals("text")) return true;
      }
    }
    return false;
  }
}
