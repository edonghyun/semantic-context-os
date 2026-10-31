package io.scos.client;

import io.scos.engine.EventLog;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 수집의 주기 실행과 JDBC를 중단하지 않는 상한 있는 종료를 소유한다. */
final class CollectionCycles implements AutoCloseable {
  static final Duration CYCLE = Duration.ofMinutes(15);
  static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);
  private static final Logger log = LoggerFactory.getLogger(CollectionCycles.class);
  private final ClientAssembly assembly;
  private final ScheduledExecutorService cycles;
  private final Runnable cycleEnded;

  private CollectionCycles(
      ClientAssembly assembly, ScheduledExecutorService cycles, Runnable cycleEnded) {
    this.assembly = assembly;
    this.cycles = cycles;
    this.cycleEnded = cycleEnded;
  }

  static CollectionCycles start(ClientAssembly assembly, ClientOptions options) {
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(CollectionCycles::collectorThread);
    CollectionCycles collection = new CollectionCycles(assembly, scheduler, options.cycleEnded());
    try {
      var unused =
          scheduler.scheduleWithFixedDelay(
              collection::cycle, 0, options.cycleInterval().toMillis(), TimeUnit.MILLISECONDS);
      return collection;
    } catch (RuntimeException | Error failure) {
      scheduler.shutdown();
      assembly.close();
      throw failure;
    }
  }

  /** 취소한 수집이 종료 상한 안에 끝나면 자원을 닫는다. JDBC 작업 중에는 강제로 닫지 않는다. */
  @Override
  public void close() {
    assembly.cancelCollection();
    // 다음 주기는 취소하고 진행 중인 주기는 cancel 확인 지점에서 끝낸다(인터럽트는 JDBC를 깨므로 쓰지 않는다).
    cycles.shutdown();
    if (awaitQuietly(cycles)) {
      assembly.close();
    } else {
      EventLog.error(log, "client.collection.shutdown_timed_out")
          .detail("operation", "client.collection.shutdown", "waitMillis", SHUTDOWN_WAIT.toMillis())
          .log();
    }
  }

  /** 수집은 검색 응답보다 뒤다: 낮은 우선순위 데몬 스레드(계획 D6 — 스케줄러에 기대는 것은 힌트일 뿐이다). */
  @SuppressWarnings("ThreadPriorityCheck")
  private static Thread collectorThread(Runnable r) {
    Thread t = new Thread(r, "scos-local-collect");
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    return t;
  }

  private void cycle() {
    try {
      assembly
          .collectOnce()
          .ifPresentOrElse(
              report -> log.info("수집 {}", report), () -> log.info("다른 프로세스가 수집 잠금을 쥐고 있어 검색만 한다"));
    } catch (RuntimeException e) {
      // 예외가 새면 이후 주기가 모두 취소된다
      EventLog.warn(log, "client.cycle.failed")
          .detail("operation", "client.collection.cycle", "errorType", e.getClass().getSimpleName())
          .log();
    } finally {
      cycleEnded.run();
    }
  }

  private static boolean awaitQuietly(ScheduledExecutorService cycles) {
    try {
      return cycles.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
