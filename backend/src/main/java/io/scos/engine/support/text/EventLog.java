package io.scos.engine.support.text;

import io.scos.spi.connector.SourceInstanceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 운영 이벤트 로그(TS SourceSyncLogger 엔트리 {@code {level, event, sourceInstanceId?, runId?, detail?}}):
 * 이벤트 이름과 필드를 SLF4J 키-값으로 싣는다. 데몬 프로필의 {@link JsonLineEncoder}가 TS와 같은 JSON 한 줄로 쓰고, 평문 프로필은
 * 메시지(이벤트 이름과 필드 요약)를 쓴다. detail에는 비밀값·원문을 넣지 않는다(TS와 같은 규칙 — 호출자가 걸러 넣는다).
 */
public final class EventLog {
  static final String EVENT = "event";
  static final String DETAIL = "detail";

  private final Logger logger;
  private final Level level;
  private final String event;
  private String sourceInstanceId;
  private String runId;
  private Map<String, Object> detail;
  private Throwable cause;

  private EventLog(Logger logger, Level level, String event) {
    this.logger = logger;
    this.level = level;
    this.event = event;
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

  /** 넣은 순서를 지키는 detail 객체: {@code detail("status", "failed", "errorKind", k)}. */
  public static Map<String, Object> detail(Object... keyValues) {
    if (keyValues.length % 2 != 0) throw new IllegalArgumentException("key-value pairs expected");
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) out.put((String) keyValues[i], keyValues[i + 1]);
    return out;
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

  public EventLog cause(Throwable cause) {
    this.cause = cause;
    return this;
  }

  public void log() {
    if (!logger.isEnabledForLevel(level)) return;
    LoggingEventBuilder b = logger.atLevel(level).addKeyValue(EVENT, event);
    if (sourceInstanceId != null) b = b.addKeyValue("sourceInstanceId", sourceInstanceId);
    if (runId != null) b = b.addKeyValue("runId", runId);
    if (detail != null) b = b.addKeyValue(DETAIL, detail);
    if (cause != null) b = b.setCause(cause);
    b.log(message());
  }

  /** 평문 로그용 요약: 이벤트 이름, 소스·run, detail(JSON). */
  String message() {
    StringBuilder out = new StringBuilder(event);
    Optional.ofNullable(sourceInstanceId).ifPresent(s -> out.append(" source=").append(s));
    Optional.ofNullable(runId).ifPresent(r -> out.append(" run=").append(r));
    Optional.ofNullable(detail).ifPresent(d -> out.append(' ').append(JsonLineEncoder.json(d)));
    return out.toString();
  }
}
