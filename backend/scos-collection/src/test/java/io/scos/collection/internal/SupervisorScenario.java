package io.scos.collection.internal;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.BackgroundThread;
import io.scos.support.CountedWorkers;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 감독 테스트의 연결을 소유한다: 메모리 포트·가짜 시계·worker 실행기(제출 수를 센다)·소스별 수집기 행동·gate. 본문은 "어떤 소스·run 기록·옵션에서 사이클을
 * 돌리면 무엇을 시도하는가"만 쓴다.
 */
final class SupervisorScenario implements AutoCloseable {
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Duration INTERVAL = Duration.ofMinutes(15);
  static final Duration RETRY_BASE = Duration.ofSeconds(30);
  static final Duration RETRY_MAX = Duration.ofMinutes(5);
  static final int CONCURRENCY = 2;
  static final Duration SNAPSHOT_INTERVAL = Duration.ofHours(1);

  /** 대기 상한 — hang 방지용이며 성공 근거가 아니다. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /** 데몬 poll 자리 — 틱이 열린 사이클의 끝을 기다리는 상한. */
  static final Duration TICK_WAIT = Duration.ofMillis(20);

  /** CANCELS_CYCLE_AND_LINGERS가 취소 뒤 수집 안에 머무는 상한 — 감독의 취소 확인 간격(100ms)보다 넉넉히 길다. */
  static final Duration LINGER = Duration.ofMillis(500);

  /** 소스별 수집기 행동. 기본은 SUCCEEDS. */
  enum Collect {
    SUCCEEDS,
    /** 고유 예외(INJECTED)를 던진다. */
    THROWS,
    /** 다른 인스턴스가 수집 중이라는 점유 경합. */
    ALREADY_RUNNING,
    /** 수집은 끝났지만 보고에 오류가 있다(failed run, errorCount 1). */
    FINISHES_WITH_ERRORS,
    /** 토큰을 취소한 뒤 성공한다(사이클 중 stop 요청의 모사). */
    CANCELS_CYCLE,
    /**
     * 토큰을 취소한 뒤 감독 호출이 돌아올 때까지(최대 LINGER) 수집 안에 머물다 성공한다 — 취소를 본 호출이 이 시도의 마감을 기다리지 않으면 그 사이에 돌아온다.
     */
    CANCELS_CYCLE_AND_LINGERS,
    /** 성공하지만, 이어지는 다음 판정의 소스 목록 읽기가 한 번 JUDGEMENT_FAILURE로 실패한다(수집을 마친 worker가 다음 소스를 집을 때). */
    BREAKS_NEXT_JUDGEMENT
  }

  static final RuntimeException INJECTED = new IllegalStateException("boom");
  static final RuntimeException JUDGEMENT_FAILURE = new IllegalStateException("storage down");

  private final MutableClock clock = new MutableClock(T0);
  private final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  private final MemoryRunStore runs = new MemoryRunStore();
  private final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  private final CountedWorkers workers = CountedWorkers.of(4);
  private final ExecutorService caller = Executors.newCachedThreadPool();
  private final AtomicBoolean failNextListing = new AtomicBoolean();
  private final Map<SourceInstanceId, Collect> behaviors = new HashMap<>();
  private final Map<SourceInstanceId, CollectionMode> collected = new ConcurrentHashMap<>();
  private final Map<SourceInstanceId, AtomicInteger> collections = new ConcurrentHashMap<>();
  private final AtomicInteger runIds = new AtomicInteger();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();
  private final CancellationToken cycleToken = new CancellationToken();
  private final CountDownLatch callReturned = new CountDownLatch(1);

  private HoldGate gate;
  private Optional<Duration> snapshotInterval = Optional.empty();
  private int concurrency = CONCURRENCY;
  private boolean listedInReverse;
  private SourceSupervisor supervisor;

  // ---- 준비(given) ----

  SourceInstanceId givenSource(String id) {
    return givenSource(id, Collect.SUCCEEDS);
  }

  SourceInstanceId givenSource(String id, Collect behavior) {
    SourceInstanceId source = new SourceInstanceId(id);
    sources.save(SourceInstances.noop(source, T0));
    behaviors.put(source, behavior);
    return source;
  }

  /** 끝난 run 기록(완료 = 시작 + 1s). */
  void givenFinished(SourceInstanceId source, RunStatus status, Instant startedAt) {
    runs.givenFinished(
        new RunId("seed" + runIds.incrementAndGet()),
        source,
        status,
        startedAt,
        startedAt.plusSeconds(1));
  }

  /** 끝나지 않고 running으로 남은 run(죽은 프로세스). */
  void givenRunningSince(SourceInstanceId source, Instant startedAt) {
    runs.start(new RunId("dead"), source, startedAt);
  }

  /** 다른 인스턴스의 점유. */
  WorkClaim givenClaimedElsewhere(SourceInstanceId source, Duration ttl, Instant at) {
    return claims
        .tryClaim(source, new OwnerId("other-host:1"), ttl, at)
        .orElseThrow(() -> new IllegalStateException("given 준비 실패: 다른 인스턴스가 점유하지 못했다"));
  }

  void givenSnapshotCompletedAt(SourceInstanceId source, Instant completedAt) {
    runs.recordSnapshotCompletion(source, completedAt, 0);
  }

  SupervisorScenario snapshotEvery(Duration interval) {
    requireNotAssembled();
    this.snapshotInterval = Optional.of(interval);
    return this;
  }

  SupervisorScenario concurrency(int workers) {
    requireNotAssembled();
    this.concurrency = workers;
    return this;
  }

  /** 저장소가 소스를 id 역순으로 준다. */
  SupervisorScenario listedInReverse() {
    requireNotAssembled();
    this.listedInReverse = true;
    return this;
  }

  /** 모든 수집이 releaseCollections()까지 수집 안에서 멈춘다. 동시 수집 수를 센다. */
  SupervisorScenario collectionsHeld() {
    this.gate = new HoldGate(WAIT_LIMIT);
    return this;
  }

  /** worker 실행기가 닫혔다(stop 뒤) — 제출은 거부된다. */
  void givenWorkersShutDown() {
    workers.shutdown();
  }

  // ---- 실행(when) ----

  /** 사이클 하나를 끝까지 돌린다. WAIT_LIMIT 안에 끝나지 않으면 실패한다. */
  SourceSupervisor.Cycle runCycle() {
    return call(WAIT_LIMIT)
        .orElseThrow(() -> new AssertionError("사이클이 " + WAIT_LIMIT + " 안에 끝나지 않았다"));
  }

  /**
   * 데몬 틱 하나: 새로 due인 소스를 빈 worker에 주고 열린 사이클의 끝을 TICK_WAIT까지 기다린다. 다른 스레드에서 돌려 WAIT_LIMIT까지만 기다린다 —
   * 틱의 대기 상한이 듣지 않아도 테스트가 실패로 끝나게. 틱이 던진 예외는 그대로 던진다.
   */
  Optional<SourceSupervisor.Cycle> tick() {
    Future<Optional<SourceSupervisor.Cycle>> tick = caller.submit(() -> call(TICK_WAIT));
    try {
      return tick.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException failure) throw failure;
      throw new IllegalStateException(e.getCause());
    } catch (TimeoutException e) {
      throw new AssertionError("틱이 " + WAIT_LIMIT + " 안에 돌아오지 않았다", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 다른 스레드에서 사이클을 시작한다(수집이 붙잡혀 있는 동안 관찰하려고). */
  Future<SourceSupervisor.Cycle> startCycleInBackground() {
    return caller.submit(this::runCycle);
  }

  SourceSupervisor.Cycle finish(Future<SourceSupervisor.Cycle> cycle) throws Exception {
    return cycle.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  /** 다른 스레드에서 사이클을 돌리고, worker 합류를 기다리는 그 스레드를 인터럽트한다(데몬 stop). */
  InterruptedCycle runCycleAndInterruptCaller() throws InterruptedException {
    InterruptedCycle cycle = new InterruptedCycle();
    cycle.caller = BackgroundThread.start("supervisor-caller", () -> cycle.result = runCycle());
    gate.awaitHeld("worker가 수집 중");
    cycle.caller.interruptAndJoin(WAIT_LIMIT);
    return cycle;
  }

  /** 띄운 worker가 전부 끝났다(마지막 판정·퇴장까지). */
  void awaitWorkersIdle() {
    workers.awaitIdle(WAIT_LIMIT);
  }

  void awaitCollectionsInFlight(int n) {
    Eventually.await("동시 수집 " + n + "개", () -> inFlight.get() >= n, WAIT_LIMIT);
  }

  void releaseCollections() {
    gate.release();
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  void resetSubmittedWorkers() {
    workers.resetSubmitted();
  }

  // ---- 관찰(then) ----

  Duration retryDelay(int recentFailures) {
    return supervisor().retryDelay(recentFailures);
  }

  Optional<Instant> nextEligibleAt(SourceInstanceId source) {
    return supervisor().nextEligibleAt(sources.get(source).orElseThrow(), clock.instant());
  }

  CollectionMode modeAt(SourceInstanceId source, Instant at) {
    return supervisor().resolveMode(sources.get(source).orElseThrow(), at);
  }

  /** 그 소스가 성공으로 수집된 횟수. */
  int collections(SourceInstanceId source) {
    AtomicInteger n = collections.get(source);
    return n == null ? 0 : n.get();
  }

  Set<SourceInstanceId> collectedSources() {
    return Set.copyOf(collected.keySet());
  }

  int collectionsInFlight() {
    return inFlight.get();
  }

  int maxCollectionsInFlight() {
    return maxInFlight.get();
  }

  int submittedWorkers() {
    return workers.submitted();
  }

  @Override
  public void close() {
    // 끝나지 않은 사이클을 기다리는 스레드가 남지 않게
    cycleToken.cancel();
    if (gate != null) gate.release();
    caller.shutdownNow();
    workers.shutdownNow();
  }

  /** 인터럽트된 감독 호출의 결과. */
  static final class InterruptedCycle {
    private BackgroundThread caller;
    private volatile SourceSupervisor.Cycle result;

    boolean returned() {
      return caller.hasReturned();
    }

    boolean callerStillInterrupted() {
      return caller.wasInterruptedAtEnd();
    }

    SourceSupervisor.Cycle cycle() {
      return result;
    }
  }

  // ---- 연결(테스트 본문에 보이지 않는다) ----

  /** 조립 때 읽는 옵션(스냅샷 간격·concurrency·목록 순서)은 첫 사이클·관찰 전에만 바꿀 수 있다 — 늦게 주면 조용히 무시되지 않게 멈춘다. */
  private void requireNotAssembled() {
    if (supervisor != null) {
      throw new IllegalStateException("given 준비 실패: 감독이 이미 조립됐다");
    }
  }

  private SourceSupervisor supervisor() {
    if (supervisor == null)
      supervisor =
          new SourceSupervisor(
              SourceInstances.listing(sources, this::listEnabled),
              runs,
              claims,
              this::collect,
              new SourceSupervisor.Options(
                  INTERVAL, RETRY_BASE, RETRY_MAX, concurrency, snapshotInterval),
              clock,
              workers);
    return supervisor;
  }

  private Optional<SourceSupervisor.Cycle> call(Duration wait) {
    try {
      return supervisor().runDueSources(cycleToken, wait);
    } finally {
      callReturned.countDown();
    }
  }

  private List<SourceInstance> listEnabled() {
    if (failNextListing.getAndSet(false)) throw JUDGEMENT_FAILURE;
    List<SourceInstance> enabled = sources.listEnabled();
    return listedInReverse ? enabled.reversed() : enabled;
  }

  private CollectionRuntime.Report collect(
      SourceInstanceId source, CollectionMode mode, CancellationToken cancel) {
    if (gate != null) {
      maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      gate.hold();
      inFlight.decrementAndGet();
    }
    return switch (behaviors.getOrDefault(source, Collect.SUCCEEDS)) {
      case SUCCEEDS -> succeed(source, mode);
      case THROWS -> throw INJECTED;
      case ALREADY_RUNNING -> throw new AlreadyRunningException(source);
      case FINISHES_WITH_ERRORS -> finishWithErrors(source);
      case CANCELS_CYCLE -> {
        cancel.cancel();
        yield succeed(source, mode);
      }
      case CANCELS_CYCLE_AND_LINGERS -> {
        cancel.cancel();
        lingerUntilCallReturns();
        yield succeed(source, mode);
      }
      case BREAKS_NEXT_JUDGEMENT -> {
        failNextListing.set(true);
        yield succeed(source, mode);
      }
    };
  }

  private void lingerUntilCallReturns() {
    try {
      callReturned.await(LINGER.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private CollectionRuntime.Report succeed(SourceInstanceId source, CollectionMode mode) {
    collected.put(source, mode);
    collections.computeIfAbsent(source, s -> new AtomicInteger()).incrementAndGet();
    RunId run = new RunId("r" + runIds.incrementAndGet());
    return Reports.of(runs.recordSucceeded(run, source, clock.instant()));
  }

  private CollectionRuntime.Report finishWithErrors(SourceInstanceId source) {
    RunId run = new RunId("r-failed");
    runs.start(run, source, clock.instant());
    return Reports.of(
        runs.finish(
            run,
            RunStatus.FAILED,
            CollectionCoverage.INCREMENTAL,
            clock.instant(),
            1,
            Optional.of("dependency_unavailable"),
            Optional.of("down"),
            0));
  }
}
