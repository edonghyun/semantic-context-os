package io.scos.connector.tiro.internal;

import io.scos.spi.connector.CallFailure;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tiro 응답 형식 검사(TS tiro-adapter.ts·tiro-api-client.ts의
 * object·cursorPage·validateParagraph·isText·latestUpdate). 투영이 읽는 필드를 체크포인트 전진 전에 확인한다. 위반은
 * invalid_provider_response(TS 메시지 그대로) — 그 노트를 불완전한 본문으로 덮어쓰지 않고 run이 실패한다(TS는 일반 Error로 errorKind
 * "Error", Java는 종류로 분류).
 */
public final class TiroResponses {
  /** 목록·문단 한 페이지: 항목과 다음 커서(마지막이면 없음). */
  public record CursorPage(List<ObjectNode> entries, Optional<String> nextCursor) {}

  private TiroResponses() {}

  public static ObjectNode object(JsonNode value) {
    if (value == null || !value.isObject()) throw invalid("Invalid Tiro object");
    return (ObjectNode) value;
  }

  /** `{content: [...], nextCursor: null | 비어 있지 않은 문자열}` — nextCursor가 없거나 빈 문자열이면 위반. */
  public static CursorPage cursorPage(JsonNode value) {
    ObjectNode page = object(value);
    JsonNode content = page.get("content");
    JsonNode next = page.get("nextCursor");
    if (content == null
        || !content.isArray()
        || next == null
        || !(next.isNull() || (next.isString() && !next.asString().isEmpty())))
      throw invalid("Invalid Tiro pagination envelope");
    List<ObjectNode> entries = new ArrayList<>();
    for (JsonNode entry : content) entries.add(object(entry));
    return new CursorPage(entries, next.isNull() ? Optional.empty() : Optional.of(next.asString()));
  }

  /** `{content: "<문자열>", ...}` 객체인가(TS isText). */
  public static boolean isText(JsonNode value) {
    return value != null
        && value.isObject()
        && value.get("content") != null
        && value.get("content").isString();
  }

  /** 문단: uuid 문자열·locked 불리언, 잠기지 않았으면 전사(있으면 텍스트)와 화자 구간(있으면 content·speaker.label 문자열). */
  public static void validateParagraph(ObjectNode paragraph) {
    JsonNode uuid = paragraph.get("uuid");
    JsonNode locked = paragraph.get("locked");
    if (uuid == null || !uuid.isString() || locked == null || !locked.isBoolean())
      throw invalid("Invalid Tiro paragraph response");
    if (locked.asBoolean()) return;
    if (present(paragraph.get("transcript")) && !isText(paragraph.get("transcript")))
      throw invalid("Invalid Tiro transcript content");
    JsonNode segments = paragraph.get("diarizedSegments");
    if (!present(segments)) return;
    if (!segments.isArray()) throw invalid("Invalid Tiro speaker segments");
    for (JsonNode value : segments) {
      ObjectNode segment = object(value);
      ObjectNode speaker = object(segment.get("speaker"));
      JsonNode personName = speaker.get("personName");
      if (!isString(segment.get("content"))
          || !isString(speaker.get("label"))
          || (present(personName) && !personName.isString()))
        throw invalid("Invalid Tiro speaker segment");
    }
  }

  /** 노트·요약·문서의 updatedAt 중 가장 늦은 값(문자열 그대로). 없는 자원은 건너뛰고, 있는데 시각이 아니면 위반. 같은 시각이면 앞의 것. */
  public static String latestUpdate(List<ObjectNode> resources) {
    String latest = null;
    long latestMillis = 0;
    for (ObjectNode resource : resources) {
      JsonNode timestamp = resource.get("updatedAt");
      if (timestamp == null) continue;
      long millis = epochMillis(timestamp);
      if (latest == null || millis > latestMillis) {
        latest = timestamp.asString();
        latestMillis = millis;
      }
    }
    if (latest == null) throw invalid("Missing Tiro update timestamp");
    return latest;
  }

  /** ISO-8601 오프셋 시각(TS Date.parse가 받는 오프셋 없는 형식은 받지 않는다 — Tiro API는 date-time을 오프셋과 함께 준다). */
  private static long epochMillis(JsonNode timestamp) {
    if (!timestamp.isString()) throw invalid("Invalid Tiro update timestamp");
    try {
      return OffsetDateTime.parse(timestamp.asString()).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      throw invalid("Invalid Tiro update timestamp");
    }
  }

  /** JS `value != null`: 키가 없거나 null이면 없음. */
  static boolean present(JsonNode value) {
    return value != null && !value.isNull();
  }

  public static boolean isString(JsonNode value) {
    return value != null && value.isString();
  }

  public static TiroProtocolException invalid(String message) {
    return new TiroProtocolException(
        CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message));
  }
}
