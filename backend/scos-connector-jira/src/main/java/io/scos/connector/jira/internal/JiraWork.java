package io.scos.connector.jira.internal;

import io.scos.engine.support.text.JsJson;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 큐의 작업 하나(`{kind, id?, key?, start?, cursor?, size?}`). 저장 형식의 JSON 객체를 그대로 지닌다 — 이어 읽기는 객체를 복사해
 * start·cursor만 바꾸므로 키 순서와 모르는 키가 그대로 남아 체크포인트 값이 저장 형식과 같다. 필드는 이름 있는 메서드로 읽는다.
 */
final class JiraWork {
  private static final BigDecimal MAX_SAFE = BigDecimal.valueOf(JsJson.MAX_SAFE_INTEGER);

  private final ObjectNode node;

  private JiraWork(ObjectNode node) {
    this.node = node;
  }

  /** 이 종류의 새 작업(다른 필드는 with…로 붙인다). */
  static JiraWork of(ObjectMapper json, WorkKind kind) {
    return new JiraWork(json.createObjectNode().put("kind", kind.wire));
  }

  /**
   * 체크포인트의 작업 하나. 알려진 종류, start는 0 이상 안전 정수, id·key·cursor는 비어 있지 않은 문자열, 루트 목록이 아니면 id가 있어야 한다.
   */
  static JiraWork parse(JsonNode entry) {
    ObjectNode work = (ObjectNode) JiraPages.object(entry);
    JsonNode kind = work.get("kind");
    JsonNode start = work.get("start");
    Optional<WorkKind> known =
        kind == null || !kind.isString() ? Optional.empty() : WorkKind.fromWire(kind.asString());
    boolean badStart = start != null && !(isSafeInteger(start) && start.asDouble() >= 0);
    boolean badText =
        List.of("id", "key", "cursor").stream()
            .map(work::get)
            .anyMatch(v -> v != null && (!v.isString() || v.asString().isEmpty()));
    if (known.isEmpty() || badStart || badText)
      throw new JiraProtocolException("Invalid Jira checkpoint work");
    JsonNode id = work.get("id");
    if (known.get().shape != WorkKind.Shape.ROOT_LISTING && (id == null || !id.isString()))
      throw new JiraProtocolException("Missing Jira checkpoint identity");
    return new JiraWork(work.deepCopy());
  }

  /** JS {@code Number.isSafeInteger}: 소수점 표기라도 정수 값(1.0·1e3)이면 받고, |값| ≤ 2^53 − 1. */
  private static boolean isSafeInteger(JsonNode value) {
    if (!value.isNumber()) return false;
    BigDecimal number = value.decimalValue();
    return number.stripTrailingZeros().scale() <= 0 && number.abs().compareTo(MAX_SAFE) <= 0;
  }

  WorkKind kind() {
    return WorkKind.of(node);
  }

  Optional<String> id() {
    return text("id");
  }

  Optional<String> key() {
    return text("key");
  }

  Optional<String> cursor() {
    return text("cursor");
  }

  /** 목록의 시작 오프셋(없으면 0). */
  long start() {
    return node.has("start") ? node.get("start").asLong() : 0;
  }

  /** 저장된 start 값 그대로(거부 위치에 싣는다). */
  Optional<JsonNode> startValue() {
    return Optional.ofNullable(node.get("start"));
  }

  /** 미리 아는 첨부 크기(수일 때만). */
  Optional<Double> size() {
    JsonNode size = node.get("size");
    return size != null && size.isNumber() ? Optional.of(size.asDouble()) : Optional.empty();
  }

  /** id를 붙인 새 작업(이 작업은 그대로). */
  JiraWork withId(String id) {
    return new JiraWork(node.deepCopy().put("id", id));
  }

  /** key를 붙인 새 작업(이 작업은 그대로). */
  JiraWork withKey(String key) {
    return new JiraWork(node.deepCopy().put("key", key));
  }

  /** size를 붙인 새 작업(이 작업은 그대로). */
  JiraWork withSize(JsonNode size) {
    ObjectNode sized = node.deepCopy();
    sized.set("size", size);
    return new JiraWork(sized);
  }

  /** 같은 작업의 다음 페이지(복사해 start 또는 cursor만 바꾼다). */
  JiraWork continuedAt(JiraPages.Continuation continuation) {
    ObjectNode continued = node.deepCopy();
    switch (continuation) {
      case JiraPages.Continuation.Offset o -> continued.put("start", o.start());
      case JiraPages.Continuation.Cursor c -> continued.put("cursor", c.token());
    }
    return new JiraWork(continued);
  }

  ObjectNode json() {
    return node;
  }

  private Optional<String> text(String field) {
    return node.has(field) ? Optional.of(node.get(field).asString()) : Optional.empty();
  }
}
