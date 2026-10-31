package io.scos.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** 인코딩 중의 추가 기록을 재현한다. root appender가 메시지를 먼저 포맷하지 않게 전용 로거를 사용한다. */
final class LogCaptureScenario implements AutoCloseable {
  private final Logger logger = (Logger) LoggerFactory.getLogger(LogCaptureScenario.class);
  private final @Nullable Level previousLevel = logger.getLevel();
  private final boolean previousAdditive = logger.isAdditive();
  private final LogCapture capture;

  LogCaptureScenario() {
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    capture = LogCapture.of(LogCaptureScenario.class);
  }

  void givenAppendDuringFormatting() {
    logger.info("{}", new DeferredMessage());
  }

  List<String> messages() {
    return capture.events().stream().map(event -> event.at("/detail/message").asString()).toList();
  }

  @Override
  public void close() {
    capture.close();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
  }

  private final class DeferredMessage {
    @Override
    public String toString() {
      logger.info("during formatting");
      return "first";
    }
  }
}
