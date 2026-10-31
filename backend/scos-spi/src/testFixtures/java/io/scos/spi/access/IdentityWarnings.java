package io.scos.spi.access;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 신원 어댑터의 JDK 경고 로그를 격리해서 관찰하고 닫을 때 연결을 해제한다. */
final class IdentityWarnings implements AutoCloseable {
  private final Logger logger;
  private final List<LogRecord> warnings = new ArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord event) {
          if (event.getLevel().equals(Level.WARNING)) warnings.add(event);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  IdentityWarnings(IdentityStore store) {
    logger = Logger.getLogger(store.getClass().getName());
    logger.addHandler(capture);
  }

  List<String> messages() {
    return warnings.stream().map(LogRecord::getMessage).toList();
  }

  @Override
  public void close() {
    logger.removeHandler(capture);
  }
}
