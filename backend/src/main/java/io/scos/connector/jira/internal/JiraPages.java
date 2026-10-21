package io.scos.connector.jira.internal;

import io.scos.engine.support.text.JsJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Jira 목록 봉투 읽기(TS readPage·completeEmbeddedCollection·identity, jira-adapter.ts:463-518). 잘리거나 빠진
 * 봉투를 마지막 페이지로 부르지 않는다 — 조용히 끝내면 남은 항목을 영영 놓친다. 위반은 {@link JiraProtocolException}(TS 메시지).
 *
 * <ul>
 *   <li>목록(startAt·total·isLast): 응답 startAt이 요청과 다르면 실패, isLast거나 받은 개수가 total에 닿으면 끝, 빈 페이지이거나
 *       (isLast가 false가 아니면서) total이 없으면 실패, 아니면 받은 개수만큼 뒤.
 *   <li>이슈 검색(nextPageToken·isLast): isLast면 끝, 아니면 비지 않고 직전과 다른 토큰이 다음 위치.
 *   <li>봉투 없는 배열은 그 자체가 전부다.
 * </ul>
 */
final class JiraPages {
  /** 다음 페이지 위치. */
  sealed interface Continuation {
    record Offset(long start) implements Continuation {}

    record Cursor(String token) implements Continuation {}
  }

  record Page(List<JsonNode> entries, Optional<Continuation> continuation) {
    static Page last(List<JsonNode> entries) {
      return new Page(entries, Optional.empty());
    }
  }

  private JiraPages() {}

  static Page listing(JsonNode data, String key, long start) {
    if (data.isArray()) return Page.last(objects(data));
    List<JsonNode> entries = entries(data, key);
    JsonNode startAt = data.get("startAt");
    if (startAt != null && startAt.isNumber() && startAt.asDouble() != start)
      throw new JiraProtocolException("Jira returned wrong page offset");
    JsonNode total = data.get("total");
    boolean hasTotal = total != null && total.isNumber();
    if (isTrue(data.get("isLast")) || (hasTotal && start + entries.size() >= total.asDouble()))
      return Page.last(entries);
    if (entries.isEmpty() || (!isFalse(data.get("isLast")) && !hasTotal))
      throw new JiraProtocolException("Incomplete Jira pagination envelope");
    return new Page(entries, Optional.of(new Continuation.Offset(start + entries.size())));
  }

  static Page search(JsonNode data, Optional<String> cursor) {
    if (data.isArray()) return Page.last(objects(data));
    List<JsonNode> entries = entries(data, "issues");
    if (isTrue(data.get("isLast"))) return Page.last(entries);
    JsonNode token = data.get("nextPageToken");
    if (token == null
        || !token.isString()
        || token.asString().isEmpty()
        || cursor.filter(token.asString()::equals).isPresent())
      throw new JiraProtocolException("Jira search did not advance");
    return new Page(entries, Optional.of(new Continuation.Cursor(token.asString())));
  }

  /**
   * 내장 자식 목록이 완전하면(total = 받은 개수, startAt 없음 또는 0) 그 목록, 아니면 empty — 끝점에서 다시 읽는다. TS의 "total은 안전한
   * 정수"는 배열 길이와 같다는 조건에 들어 있다.
   */
  static Optional<List<JsonNode>> completeEmbedded(JsonNode value, String key) {
    if (value == null || !value.isObject()) return Optional.empty();
    JsonNode entries = value.get(key);
    JsonNode total = value.get("total");
    JsonNode startAt = value.get("startAt");
    if (entries == null
        || !entries.isArray()
        || total == null
        || !total.isNumber()
        || total.asDouble() != entries.size()
        || (startAt != null && !(startAt.isNumber() && startAt.asDouble() == 0)))
      return Optional.empty();
    return Optional.of(objects(entries));
  }

  /** 문자열(비지 않은) 또는 숫자 id. TS {@code String(id)}라 수는 JS 표기다. */
  static String identity(JsonNode value) {
    JsonNode id = value.get("id");
    if (id == null || !(id.isString() || id.isNumber()) || id.asString().isEmpty())
      throw new JiraProtocolException("Jira object has no identity");
    return id.isNumber() ? JsJson.stringify(id) : id.asString();
  }

  /** 객체여야 한다(TS object()). */
  static JsonNode object(JsonNode value) {
    if (value == null || !value.isObject()) throw new JiraProtocolException("Invalid Jira object");
    return value;
  }

  private static List<JsonNode> entries(JsonNode data, String key) {
    object(data);
    JsonNode list = data.get(key);
    if (list == null || !list.isArray())
      throw new JiraProtocolException("Invalid Jira collection response");
    return objects(list);
  }

  private static List<JsonNode> objects(JsonNode array) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode entry : array) out.add(object(entry));
    return List.copyOf(out);
  }

  private static boolean isTrue(JsonNode node) {
    return node != null && node.isBoolean() && node.asBoolean();
  }

  private static boolean isFalse(JsonNode node) {
    return node != null && node.isBoolean() && !node.asBoolean();
  }
}
