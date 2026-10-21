package io.scos.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.scos.collection.internal.SourceSupervisor;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 사이클 결과의 마지막 상태(health가 읽는다)와 사이클 지표(시간·시도 ok/failed). 로그는 요약만(소스 id·원문 없음). */
public final class DaemonObserver {
  public static final String CYCLE = "scos.supervisor.cycle";
  public static final String ATTEMPTS = "scos.supervisor.attempts";
  private static final Logger log = LoggerFactory.getLogger(DaemonObserver.class);
  private final AtomicReference<CollectionDaemon.CycleOutcome> last = new AtomicReference<>();
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
    log.info(
        "cycle: attempts={} failed={} deferred={} projections={} error={}",
        attempts.size(),
        failed,
        outcome.collection().map(c -> c.deferred().size()).orElse(0),
        outcome.projections().size(),
        outcome.error().map(e -> e.getClass().getSimpleName()).orElse("-"));
  }

  private Counter attempts(String outcome) {
    return Counter.builder(ATTEMPTS).tag("outcome", outcome).register(registry);
  }

  public Optional<CollectionDaemon.CycleOutcome> last() {
    return Optional.ofNullable(last.get());
  }
}
