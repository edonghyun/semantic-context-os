package io.scos.client;

import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.CollectionTask;
import io.scos.engine.SearchQuery;
import io.scos.spi.store.Principal;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import io.scos.support.LogCapture;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * 실제 주기 스케줄러와 운영 조립을 짧은 주기로 실행한다. 첫 주기는 실패하고 둘째 주기는 실제로 수집하며, 셋째 주기는 시작하자마자 붙잡힌다. 셋째 주기의 시작이 "둘째
 * 주기가 끝났고 예약이 이어진다"는 관찰 지점이고, 붙잡아 둔 동안에는 다른 주기가 돌지 않으므로 검색·로그 관찰이 진행 중인 수집과 겹치지 않는다.
 */
final class ClientCycleScenario implements AutoCloseable {
  private static final Duration INTERVAL = Duration.ofMillis(10);

  /** hang 방지 상한. 실제 수집·투영이 걸리는 시간은 부하에 따라 달라서 이 안에 끝났다는 것을 근거로 쓰지 않는다. */
  private static final Duration HANG_GUARD = Duration.ofSeconds(10);

  private final AtomicInteger attempts = new AtomicInteger();
  private final HoldGate thirdCycle = new HoldGate(HANG_GUARD);
  private final LogCapture logs = LogCapture.of(CollectionCycles.class);
  private final ClientAssembly assembly;
  private CollectionCycles cycles;
  private volatile Thread worker;
  private List<String> recoveredSessionIds = List.of();
  private boolean stopped;

  ClientCycleScenario(Path root) throws IOException {
    var installation = new LocalInstallation(root, ScosLocalScenario.CLOCK);
    installation.givenProject();
    assembly = ClientAssembly.open(installation.paths(), options());
  }

  private ClientOptions options() {
    return new ClientOptions(
        ScosLocalScenario.CLOCK, this::failingFirst, this::cycleEnded, delay -> false, INTERVAL);
  }

  private void cycleEnded() {
    worker = Thread.currentThread();
  }

  boolean runnerTerminated() {
    return worker != null && !worker.isAlive();
  }

  private CollectionTask failingFirst(CollectionTask real) {
    return new CollectionTask() {
      @Override
      public CollectionReport runOnce() {
        int attempt = attempts.incrementAndGet();
        if (attempt == 1) throw new IllegalStateException("synthetic cycle failure");
        if (attempt >= 3) thirdCycle.hold();
        return real.runOnce();
      }

      @Override
      public CollectionReport fullReproject() {
        return real.fullReproject();
      }

      @Override
      public void cancel() {
        real.cancel();
      }
    };
  }

  void awaitRecovery() {
    cycles = CollectionCycles.start(assembly, options());
    try {
      thirdCycle.awaitHeld("실패 뒤 주기가 끝나고 다음 예약 주기가 시작되어야 한다");
      recoveredSessionIds = findSessionIds();
    } finally {
      stopCycles();
    }
  }

  List<String> sessionIds() {
    return recoveredSessionIds;
  }

  private List<String> findSessionIds() {
    return assembly
        .engine()
        .search(Principal.singleUser(), SearchQuery.of("대체는 별로"))
        .hits()
        .stream()
        .map(hit -> hit.documentId())
        .distinct()
        .toList();
  }

  List<JsonNode> failures() {
    if (!stopped) throw new IllegalStateException("수집 스레드 종료 뒤에만 로그를 관찰한다");
    return logs.events("client.cycle.failed");
  }

  private void stopCycles() {
    if (stopped) return;
    thirdCycle.release();
    cycles.close();
    Eventually.await("수집 예약 스레드 종료", this::runnerTerminated, HANG_GUARD);
    stopped = true;
  }

  @Override
  public void close() {
    try {
      if (cycles != null) stopCycles();
      else assembly.close();
    } finally {
      logs.close();
    }
  }
}
