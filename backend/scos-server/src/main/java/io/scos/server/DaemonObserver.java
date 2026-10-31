package io.scos.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.scos.collection.internal.SourceSupervisor;
import io.scos.engine.EventLog;
import io.scos.engine.support.logging.EventValues;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사이클 결과의 마지막 상태(health가 읽는다)와 사이클 지표(시간·시도 ok/failed). 로그는 요약 이벤트 하나(소스 id·원문 없음)다 — 숫자가 바로 앞 사이클과
 * 다르거나 실패·오류가 있으면 info, 나머지는 debug(logging.md §3: 운영 로그를 주기 요약이 채우지 않게).
 */
public final class DaemonObserver {
  public static final String CYCLE = "scos.supervisor.cycle";
  public static final String ATTEMPTS = "scos.supervisor.attempts";
  private static final String CYCLE_EVENT = "daemon.cycle.completed";
  private static final Logger log = LoggerFactory.getLogger(DaemonObserver.class);
  private final AtomicReference<CollectionDaemon.CycleOutcome> last = new AtomicReference<>();
  private final AtomicReference<Summary> lastSummary = new AtomicReference<>();
  private final MeterRegistry registry;

  public DaemonObserver(MeterRegistry registry) {
    this.registry = registry;
  }

  void record(CollectionDaemon.CycleOutcome outcome) {
    last.set(outcome);
    List<SourceSupervisor.Attempt> attempts =
        outcome.collection().map(SourceSupervisor.Cycle::attempts).orElse(List.of());
    outcome
        .collection()
        .ifPresent(
            c ->
                Timer.builder(CYCLE)
                    .register(registry)
                    .record(Duration.between(c.startedAt(), outcome.finishedAt())));
    long failed = attempts.stream().filter(SourceSupervisor.Attempt::failed).count();
    long ok = attempts.size() - failed;
    if (failed > 0) attempts("failed").increment((double) failed);
    if (ok > 0) attempts("ok").increment((double) ok);
    Summary summary =
        new Summary(
            attempts.size(),
            failed,
            outcome.collection().map(c -> c.deferred().size()).orElse(0),
            outcome.projections().size(),
            outcome.error().map(e -> e.getClass().getSimpleName()));
    Summary previous = lastSummary.getAndSet(summary);
    boolean notable = !summary.equals(previous) || failed > 0 || summary.errorType().isPresent();
    (notable ? EventLog.info(log, CYCLE_EVENT) : EventLog.debug(log, CYCLE_EVENT))
        .detail(summary.detail())
        .log();
  }

  /** 사이클 요약의 숫자(소스 id·원문 없음). 바로 앞 사이클과 비교해 수준을 고른다. */
  private record Summary(
      int attempts, long failed, int deferred, int projections, Optional<String> errorType) {
    Map<String, Object> detail() {
      Map<String, Object> detail =
          EventValues.detail(
              "attempts",
              attempts,
              "failed",
              failed,
              "deferred",
              deferred,
              "projections",
              projections);
      errorType.ifPresent(t -> detail.put("errorType", t));
      return detail;
    }
  }

  private Counter attempts(String outcome) {
    return Counter.builder(ATTEMPTS).tag("outcome", outcome).register(registry);
  }

  public Optional<CollectionDaemon.CycleOutcome> last() {
    return Optional.ofNullable(last.get());
  }
}
