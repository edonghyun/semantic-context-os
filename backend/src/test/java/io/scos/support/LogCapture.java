package io.scos.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.scos.engine.support.text.JsonLineEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 한 클래스의 로그를 잡아 데몬 JSON 줄({@link JsonLineEncoder})로 읽는다. 닫으면 떼어 낸다. 줄의 ts는 실제 시각이라 비교하지 않는다 —
 * {@link #events}는 ts를 뺀 나머지다.
 */
public final class LogCapture implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final JsonLineEncoder encoder = new JsonLineEncoder();

  private LogCapture(Class<?> owner) {
    logger = (Logger) LoggerFactory.getLogger(owner);
    appender.start();
    logger.addAppender(appender);
  }

  public static LogCapture of(Class<?> owner) {
    return new LogCapture(owner);
  }

  /** 잡은 로그 줄(ts를 뺀 JSON), 남긴 순서. */
  public List<JsonNode> events() {
    return appender.list.stream()
        .map(e -> JSON.readTree(new String(encoder.encode(e), StandardCharsets.UTF_8)))
        .map(n -> (JsonNode) ((ObjectNode) n).without("ts"))
        .toList();
  }

  /** 이 이벤트 이름의 줄. */
  public List<JsonNode> events(String event) {
    return events().stream().filter(n -> n.path("event").asString("").equals(event)).toList();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
  }
}
