package io.scos.engine;

import io.scos.engine.support.logging.EventValues;
import io.scos.engine.support.logging.JsonLineEncoder;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.source.SourceInstanceId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 운영 이벤트 로그(엔트리 {@code {level, event, sourceInstanceId?, runId?, detail?}}): 이벤트 이름과 필드를 SLF4J
 * 키-값으로 싣는다. 기계 갈래(데몬 serve.jsonl)는 {@link JsonLineEncoder}가 키-값을 JSON 한 줄로 쓰고, 사람 갈래(텍스트)는
 * 메시지({@link #message()})를 쓴다. detail에는 비밀값·원문을 넣지 않는다(호출자가 걸러 넣는다).
 */
public final class EventLog {
  private static final Pattern BARE_UNSAFE = Pattern.compile("[\\s=\"\\p{Cntrl}]");

  private final Logger logger;
  private final Level level;
  private final String event;
  private @Nullable String sourceInstanceId;
  private @Nullable String runId;
  private @Nullable Map<String, Object> detail;
  private @Nullable Throwable cause;

  private EventLog(Logger logger, Level level, String event) {
    this.logger = logger;
    this.level = level;
    this.event = event;
  }

  public static EventLog debug(Logger logger, String event) {
    return new EventLog(logger, Level.DEBUG, event);
  }

  public static EventLog info(Logger logger, String event) {
    return new EventLog(logger, Level.INFO, event);
  }

  public static EventLog warn(Logger logger, String event) {
    return new EventLog(logger, Level.WARN, event);
  }

  public static EventLog error(Logger logger, String event) {
    return new EventLog(logger, Level.ERROR, event);
  }

  public EventLog source(SourceInstanceId id) {
    this.sourceInstanceId = id.value();
    return this;
  }

  public EventLog run(String runId) {
    this.runId = runId;
    return this;
  }

  public EventLog detail(Map<String, Object> detail) {
    this.detail = detail;
    return this;
  }

  /** 넣은 순서로 detail 키와 값을 싣는다. 값에는 비밀이나 원문을 넣지 않는다. */
  public EventLog detail(Object... keyValues) {
    return detail(EventValues.detail(keyValues));
  }

  public EventLog cause(Throwable cause) {
    this.cause = cause;
    return this;
  }

  public void log() {
    if (!logger.isEnabledForLevel(level)) return;
    LoggingEventBuilder b = logger.atLevel(level).addKeyValue("event", event);
    if (sourceInstanceId != null) b = b.addKeyValue("sourceInstanceId", sourceInstanceId);
    if (runId != null) b = b.addKeyValue("runId", runId);
    if (detail != null) b = b.addKeyValue("detail", detail);
    if (cause != null) b = b.setCause(cause);
    b.log(message());
  }

  /**
   * 사람 갈래 메시지(logging.md §1.1): 이벤트 이름, `source=`·`run=`, detail을 넣은 순서대로 `key=value`. detail 안의
   * 객체는 펼쳐 안쪽 키로 쓰고, 그 키가 같은 줄의 다른 키와 겹치면 `바깥.안쪽`으로 쓴다.
   */
  String message() {
    StringBuilder out = new StringBuilder(event);
    Optional.ofNullable(sourceInstanceId).ifPresent(s -> pair(out, "source", s));
    Optional.ofNullable(runId).ifPresent(r -> pair(out, "run", r));
    if (detail == null) return out.toString();
    Map<String, Integer> uses = new HashMap<>();
    for (Map.Entry<String, Object> e : detail.entrySet())
      if (e.getValue() instanceof Map<?, ?> inner)
        inner.keySet().forEach(k -> uses.merge(String.valueOf(k), 1, Integer::sum));
      else uses.merge(e.getKey(), 1, Integer::sum);
    for (Map.Entry<String, Object> e : detail.entrySet()) {
      if (!(e.getValue() instanceof Map<?, ?> inner)) {
        pair(out, e.getKey(), e.getValue());
        continue;
      }
      for (Map.Entry<?, ?> i : inner.entrySet()) {
        String key = String.valueOf(i.getKey());
        pair(out, uses.getOrDefault(key, 0) > 1 ? e.getKey() + "." + key : key, i.getValue());
      }
    }
    return out.toString();
  }

  private static void pair(StringBuilder out, String key, @Nullable Object value) {
    out.append(' ').append(key).append('=').append(text(value));
  }

  /** 값 → 텍스트: 배열은 `[a,b]`, 공백·`=`·따옴표·제어 문자가 들거나 빈 문자열은 JSON 문자열, 그 밖은 그대로. */
  private static String text(@Nullable Object value) {
    if (value instanceof Collection<?> list) {
      StringJoiner out = new StringJoiner(",", "[", "]");
      list.forEach(element -> out.add(text(element)));
      return out.toString();
    }
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number || value instanceof Map)
      return EventValues.json(value);
    String s = value instanceof JsonNode node ? JsJson.stringify(node) : String.valueOf(value);
    return s.isEmpty() || BARE_UNSAFE.matcher(s).find() ? JsJson.string(s) : s;
  }
}
