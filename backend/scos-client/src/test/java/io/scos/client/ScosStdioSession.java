package io.scos.client;

import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.CollectionTask;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

/** ClientMain.run을 다른 스레드에서 돌리는 mcp 세션. */
final class ScosStdioSession implements AutoCloseable {
  /** 수집이 막혀 있어도 검색 응답이 와야 하는 상한. */
  private static final Duration ANSWER_WAIT = Duration.ofSeconds(10);

  /** 스키마·토크나이저를 처음 여는 준비 단계의 상한. */
  private static final Duration STARTUP_WAIT = Duration.ofSeconds(10);

  /** 응답·정리 실패는 PIT 감시보다 먼저 끝낸다. */
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /** 운영 종료 상한 10초를 실제로 넘기는 프로세스 사례의 관측 상한. */
  private static final Duration SHUTDOWN_OBSERVATION_WAIT = Duration.ofSeconds(20);

  private static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
  private static final String INITIALIZED =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
  private static final int SEARCH_ID = 2;
  private static final String SEARCH =
      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"search_context\",\"arguments\":{\"query\":\"대체는 별로\"}}}";

  private final StdioLines.LineInput stdin = new StdioLines.LineInput();
  private final StdioLines.LineOutput stdout = new StdioLines.LineOutput();
  private final ExecutorService runner = Executors.newSingleThreadExecutor();
  private final Future<Integer> exit;
  private final Duration shutdownWait;

  private final AtomicBoolean cycled = new AtomicBoolean();
  private final AtomicInteger runs = new AtomicInteger();
  private final HoldGate gate = new HoldGate(STARTUP_WAIT);
  private final AtomicBoolean finished = new AtomicBoolean();

  static ScosStdioSession collected(Map<String, String> env) {
    ScosStdioSession session = new ScosStdioSession(env);
    return session.prepare(
        () -> {
          session.handshake();
          session.awaitCycle();
        });
  }

  static ScosStdioSession blocked(Map<String, String> env) {
    ScosStdioSession session = new ScosStdioSession(env, true);
    return session.prepare(() -> session.gate.awaitHeld("given 준비 실패: 수집 시작"));
  }

  static ScosStdioSession readOnly(Map<String, String> env) {
    ScosStdioSession session = new ScosStdioSession(env);
    return session.prepare(
        () -> {
          session.awaitCycle();
          session.handshake();
        });
  }

  ScosStdioSession(
      Map<String, String> env, UnaryOperator<CollectionTask> collectors, Runnable cycleEnded) {
    this(env, collectors, cycleEnded, WAIT_LIMIT);
  }

  static ScosStdioSession observingShutdownTimeout(
      Map<String, String> env, UnaryOperator<CollectionTask> collectors) {
    return new ScosStdioSession(env, collectors, () -> {}, SHUTDOWN_OBSERVATION_WAIT);
  }

  private ScosStdioSession(
      Map<String, String> env,
      UnaryOperator<CollectionTask> collectors,
      Runnable cycleEnded,
      Duration shutdownWait) {
    this.shutdownWait = shutdownWait;
    exit =
        runner.submit(
            () ->
                ClientMain.run(
                    new String[] {"mcp"},
                    stdin,
                    stdout,
                    env,
                    new ClientOptions(
                        ScosLocalScenario.CLOCK,
                        collectors,
                        cycleEnded,
                        delay -> false,
                        CollectionCycles.CYCLE)));
  }

  private ScosStdioSession(Map<String, String> env) {
    this(env, false);
  }

  private ScosStdioSession(Map<String, String> env, boolean block) {
    shutdownWait = WAIT_LIMIT;
    exit =
        runner.submit(
            () ->
                ClientMain.run(
                    new String[] {"mcp"},
                    stdin,
                    stdout,
                    env,
                    new ClientOptions(
                        ScosLocalScenario.CLOCK,
                        delegate -> observing(delegate, block),
                        () -> cycled.set(true),
                        delay -> false,
                        CollectionCycles.CYCLE)));
  }

  private CollectionTask observing(CollectionTask delegate, boolean block) {
    return new CollectionTask() {
      @Override
      public CollectionReport runOnce() {
        runs.incrementAndGet();
        if (block) gate.hold();
        finished.set(true);
        return delegate.runOnce();
      }

      @Override
      public CollectionReport fullReproject() {
        return delegate.fullReproject();
      }

      @Override
      public void cancel() {
        gate.release();
        delegate.cancel();
      }
    };
  }

  ScosStdioSession prepare(Runnable preparation) {
    try {
      preparation.run();
      return this;
    } catch (RuntimeException | Error failure) {
      boolean interrupted = Thread.interrupted();
      try {
        close();
      } catch (Exception | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      throw failure;
    }
  }

  boolean terminated() {
    return runner.isTerminated();
  }

  private void awaitCycle() {
    Eventually.await("given 준비 실패: 첫 수집 주기", cycled::get, STARTUP_WAIT);
  }

  JsonNode initialization() throws InterruptedException {
    return response(1, WAIT_LIMIT).path("result");
  }

  JsonNode search() throws InterruptedException {
    stdin.send(SEARCH);
    return response(SEARCH_ID, WAIT_LIMIT);
  }

  JsonNode searchWhileBlocked() throws InterruptedException {
    handshake();
    stdin.send(SEARCH);
    return response(SEARCH_ID, ANSWER_WAIT);
  }

  /** 상한 안에 응답이 없으면 실행기를 중단해 후속 정리 대기가 누적되지 않게 한다. */
  private JsonNode response(int id, Duration wait) throws InterruptedException {
    JsonNode response = stdout.response(id, wait);
    if (response.isMissingNode()) runner.shutdownNow();
    return response;
  }

  void sendSearch() {
    stdin.send(SEARCH);
  }

  List<String> lines() {
    return stdout.lines();
  }

  boolean collectionFinished() {
    return finished.get();
  }

  int collections() {
    return runs.get();
  }

  void handshake() {
    stdin.send(INITIALIZE);
    stdin.send(INITIALIZED);
  }

  /** stdin을 닫고 종료 코드를 기다린다. 상한을 넘긴 세션은 중단해 close에서 정상 종료를 다시 기다리지 않는다. */
  int finish() throws Exception {
    stdin.end();
    try {
      return exit.get(shutdownWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException failure) {
      runner.shutdownNow();
      throw failure;
    }
  }

  /** 정상 세션은 stdin EOF로 닫고, 대기가 실패해 이미 중단한 세션은 실행기만 거둔다. */
  @Override
  public void close() throws Exception {
    try {
      gate.release();
      if (!runner.isShutdown()) finish();
    } catch (Exception | Error failure) {
      try {
        stopRunner();
      } catch (Exception | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    stopRunner();
  }

  private void stopRunner() throws InterruptedException, TimeoutException {
    runner.shutdownNow();
    if (!runner.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new TimeoutException("stdio 실행기가 종료되지 않았다");
    }
  }
}
