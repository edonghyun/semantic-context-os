package io.scos.client;

import io.scos.engine.EventLog;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 일시적인 색인 열기 실패를 재시도하고, 복구할 수 없으면 원문 수집을 이어 간다. */
final class ClientStartup {
  static final int OPEN_ATTEMPTS = 3;
  static final Duration OPEN_RETRY = Duration.ofSeconds(1);
  private static final Logger log = LoggerFactory.getLogger(ClientStartup.class);
  private final ClientPaths paths;
  private final ClientOptions options;

  ClientStartup(ClientPaths paths, ClientOptions options) {
    this.paths = paths;
    this.options = options;
  }

  Optional<ClientAssembly> open() {
    Optional<ClientAssembly> assembly =
        openWithRetry(
            () -> ClientAssembly.open(paths, options),
            OPEN_ATTEMPTS,
            OPEN_RETRY,
            options.retryCancelled());
    if (assembly.isEmpty()) {
      EventLog.error(log, "client.index.unavailable")
          .detail("operation", "client.index.open")
          .log();
      collectStateOnly();
    }
    return assembly;
  }

  /** 색인 없이 state에만 한 번 수집한다. 이 경로의 실패도 종료 코드를 바꾸지 않으므로 한 줄만 남긴다. */
  private void collectStateOnly() {
    try (CollectionSession collection = CollectionSession.stateOnly(paths, options)) {
      collection
          .collectOnce()
          .ifPresentOrElse(
              report -> log.info("state 수집(색인 없음): {}", report),
              () -> log.info("다른 프로세스가 수집 잠금을 쥐고 있어 수집하지 않는다"));
    } catch (RuntimeException e) {
      EventLog.error(log, "client.state.failed")
          .detail("operation", "client.state.collect", "errorType", e.getClass().getSimpleName())
          .log();
    }
  }

  /** 열기를 {@code attempts}번까지 시도한다. 실패마다 한 줄을 남기고 {@code delay}만큼 쉰다. 모두 실패하면 empty. */
  static <T> Optional<T> openWithRetry(
      Supplier<T> open, int attempts, Duration delay, Predicate<Duration> retryCancelled) {
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return Optional.of(open.get());
      } catch (RuntimeException e) {
        EventLog.warn(log, "client.index.open_retry")
            .detail(
                "operation",
                "client.index.open",
                "attempt",
                attempt,
                "maxAttempts",
                attempts,
                "errorType",
                e.getClass().getSimpleName())
            .log();
        if (attempt == attempts) break;
        if (retryCancelled.test(delay)) break;
      }
    }
    return Optional.empty();
  }
}
