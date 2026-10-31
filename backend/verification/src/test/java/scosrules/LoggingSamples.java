package scosrules;

import io.scos.engine.EventLog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LocationAwareLogger;

/**
 * 로깅 규칙(io.scos.LoggingRulesTest)이 실제로 잡는지 보는 표본. 패키지가 io.scos 밖이라 본 규칙의 대상이 아니다. 이름이 곧 기대다:
 * Violates…는 규칙에 걸려야 하고 Passes…는 걸리지 않아야 한다.
 */
public final class LoggingSamples {
  private static final Logger log = LoggerFactory.getLogger(LoggingSamples.class);

  private LoggingSamples() {}

  /** SLF4J Logger를 넓힌 타입: 이 타입으로 부른 warn도 SLF4J 호출이다. */
  public interface WideLogger extends Logger {}

  /** info·debug 평문은 진단용으로 허용된다(logging.md §6). */
  public static final class PassesInfoAndDebug {
    void run() {
      log.info("started");
      log.debug("detail");
      log.atInfo().log("started");
    }
  }

  public static final class PassesEventLog {
    void run() {
      EventLog.warn(log, "sample.item.rejected").log();
      EventLog.error(log, "sample.run.failed").log();
    }
  }

  public static final class ViolatesWarn {
    void run() {
      log.warn("plain warn");
    }
  }

  public static final class ViolatesError {
    void run(RuntimeException e) {
      log.error("plain error", e);
    }
  }

  public static final class ViolatesAtWarn {
    void run() {
      log.atWarn().log("fluent warn");
    }
  }

  public static final class ViolatesAtError {
    void run() {
      log.atError().log("fluent error");
    }
  }

  /** 수준을 값으로 받는 atLevel은 정적으로 수준을 알 수 없어 통째로 막는다. */
  public static final class ViolatesAtLevel {
    void run() {
      log.atLevel(Level.INFO).log("any level");
    }
  }

  public static final class ViolatesWarnThroughSubtype {
    void run(WideLogger wide) {
      wide.warn("plain warn");
    }
  }

  /** 메서드 참조도 warn을 부르는 통로다. */
  public static final class ViolatesMethodRef {
    void run(List<String> messages) {
      messages.forEach(log::warn);
    }
  }

  /** makeLoggingEventBuilder는 수준을 값으로 받는 fluent 진입점이다. */
  public static final class ViolatesMakeBuilder {
    void run() {
      log.makeLoggingEventBuilder(Level.WARN).log("built warn");
    }
  }

  /** LocationAwareLogger.log는 수준을 정수로 받는다. */
  public static final class ViolatesLocationAwareLog {
    void run(LocationAwareLogger aware) {
      aware.log(null, "fqcn", LocationAwareLogger.WARN_INT, "aware warn", null, null);
    }
  }
}
