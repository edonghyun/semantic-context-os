package io.scos.engine.support.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import io.scos.engine.EventLog;
import io.scos.engine.support.text.JsJson;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;

/**
 * 데몬 기계 갈래(serve.jsonl)를 JSON 한 줄로 쓰는 logback 인코더(ADR-0023 줄 모양, logging.md §1.2): {@code
 * {"ts","level","event","sourceInstanceId"?,"runId"?,"detail"?,"error"?}}. 수준은 소문자
 * 이름(debug·info·warn·error — TRACE는 debug)이고, {@code status --log}는 {@code "level":"warn|error"}로
 * 경고 줄을 찾는다.
 *
 * <ul>
 *   <li>이벤트 로그({@link EventLog}): 키-값 event·sourceInstanceId·runId·detail만 쓴다. 직렬화는 JS {@code
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
  public byte @Nullable [] headerBytes() {
    return null;
  }

  @Override
  public byte @Nullable [] footerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    StringBuilder out = new StringBuilder("{\"ts\":");
    out.append(JsJson.string(ISO_MILLIS.format(event.getInstant())));
    out.append(",\"level\":").append(JsJson.string(level(event.getLevel())));
    Optional<Object> name = value(event, "event");
    if (name.isPresent()) {
      out.append(",\"event\":").append(JsJson.string(String.valueOf(name.get())));
      for (String key : TOP_LEVEL)
        value(event, key)
            .ifPresent(
                v -> out.append(",\"").append(key).append("\":").append(EventValues.json(v)));
      value(event, "detail").ifPresent(v -> out.append(",\"detail\":").append(EventValues.json(v)));
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
}
