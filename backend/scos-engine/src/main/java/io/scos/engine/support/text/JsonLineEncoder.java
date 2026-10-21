package io.scos.engine.support.text;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;

/**
 * 데몬 로그를 TS JsonLineLogger(source-sync-runtime, 운영 serve.log)와 같은 JSON 한 줄로 쓰는 logback
 * 인코더(ADR-0023): {@code {"ts","level","event","sourceInstanceId"?,"runId"?,"detail"?}}. 수준은 TS
 * 이름(debug·info·warn·error — TRACE는 debug)이고, 운영 status.sh는 {@code "level":"warn|error"}를 grep한다.
 *
 * <ul>
 *   <li>이벤트 로그({@link EventLog}): 키-값 event·sourceInstanceId·runId·detail만 쓴다. 직렬화는 TS {@code
 *       JSON.stringify}와 바이트 동등({@link JsJson}). 원인 예외가 있으면 끝에 {@code "error"}를 더한다(Java 추가 — TS는
 *       메시지만 싣는다).
 *   <li>그 밖의 로그: event {@code "log"}, detail {@code {"logger","message","error"?}}.
 *   <li>MDC와 그 밖의 키-값은 쓰지 않는다 — 넘긴 detail 밖의 값(토큰 등)이 섞여 들어갈 통로를 두지 않는다.
 * </ul>
 */
public final class JsonLineEncoder extends EncoderBase<ILoggingEvent> {
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final List<String> TOP_LEVEL = List.of("sourceInstanceId", "runId");

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    StringBuilder out = new StringBuilder("{\"ts\":");
    out.append(JsJson.string(ISO_MILLIS.format(event.getInstant())));
    out.append(",\"level\":").append(JsJson.string(level(event.getLevel())));
    Optional<Object> name = value(event, EventLog.EVENT);
    if (name.isPresent()) {
      out.append(",\"event\":").append(JsJson.string(String.valueOf(name.get())));
      for (String key : TOP_LEVEL)
        value(event, key)
            .ifPresent(v -> out.append(",\"").append(key).append("\":").append(json(v)));
      value(event, EventLog.DETAIL).ifPresent(v -> out.append(",\"detail\":").append(json(v)));
      if (event.getThrowableProxy() != null)
        out.append(",\"error\":").append(error(event.getThrowableProxy()));
    } else {
      out.append(",\"event\":\"log\",\"detail\":{\"logger\":")
          .append(JsJson.string(event.getLoggerName()))
          .append(",\"message\":")
          .append(JsJson.string(event.getFormattedMessage()));
      if (event.getThrowableProxy() != null)
        out.append(",\"error\":").append(error(event.getThrowableProxy()));
      out.append('}');
    }
    return out.append("}\n").toString().getBytes(StandardCharsets.UTF_8);
  }

  static String level(Level level) {
    return switch (level.toInt()) {
      case Level.ERROR_INT -> "error";
      case Level.WARN_INT -> "warn";
      case Level.INFO_INT -> "info";
      default -> "debug";
    };
  }

  private static Optional<Object> value(ILoggingEvent event, String key) {
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs == null) return Optional.empty();
    for (KeyValuePair pair : pairs)
      if (pair.key.equals(key)) return Optional.ofNullable(pair.value);
    return Optional.empty();
  }

  private static String error(IThrowableProxy proxy) {
    return "{\"type\":"
        + JsJson.string(proxy.getClassName())
        + ",\"message\":"
        + (proxy.getMessage() == null ? "null" : JsJson.string(proxy.getMessage()))
        + ",\"stack\":"
        + JsJson.string(ThrowableProxyUtil.asString(proxy))
        + "}";
  }

  /** 키-값 값 → JSON(TS JSON.stringify와 같은 표기). Map은 넣은 순서, 그 밖의 객체는 문자열. */
  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof JsonNode node) return JsJson.stringify(node);
    if (value instanceof String s) return JsJson.string(s);
    if (value instanceof Boolean b) return b.toString();
    if (value instanceof Number n) return JsJson.number(n.doubleValue());
    if (value instanceof Map<?, ?> map) {
      StringBuilder out = new StringBuilder("{");
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (out.length() > 1) out.append(',');
        out.append(JsJson.string(String.valueOf(e.getKey())))
            .append(':')
            .append(json(e.getValue()));
      }
      return out.append('}').toString();
    }
    if (value instanceof Collection<?> list) {
      StringBuilder out = new StringBuilder("[");
      for (Object element : list) {
        if (out.length() > 1) out.append(',');
        out.append(json(element));
      }
      return out.append(']').toString();
    }
    return JsJson.string(String.valueOf(value));
  }
}
