package io.scos.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RateLimitedProvider;
import io.scos.collection.internal.Reports;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceSupervisor;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.collection.internal.projection.MemoryProjection;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.support.BackgroundThread;
import io.scos.support.CountedWorkers;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.health.contributor.Status;

/**
 * 데몬 테스트의 연결을 소유한다: 메모리 포트·감독·투영·시계·worker·관측자·대기. 본문은 "어떤 소스와 수집기로 시작해 무엇을 기다리고 무엇을 관찰하는가"만 쓴다. 관찰
 * 메서드는 업무 질문에 답하고 내부 포트·감독 객체를 꺼내 주지 않는다. 실제 수집 런타임 조립은 MemoryCollectionRuntime이 맡는다.
 */
final class DaemonScenario implements AutoCloseable {
  static final Instant T0 = MemoryProjection.T0;

  /** 테스트 시간 안에 두 번째 사이클이 오지 않는 poll — "한 사이클만 돈다"를 시간에 기대지 않고 만든다. */
  static final Duration ONE_CYCLE_POLL = Duration.ofHours(1);

  /** 여러 사이클이 빨리 돌게 하는 poll. 사이클 수는 관측으로 확인한다. */
  static final Duration FAST_POLL = Duration.ofMillis(50);

  static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
  static final Duration MAINTENANCE_INTERVAL = Duration.ofHours(24);
  static final Duration STALL_AFTER = Duration.ofMinutes(30);
  static final String COLLECTOR_FAILURE = "boom";
  static final String STORAGE_FAILURE = "storage down";

  /** 조건 대기의 상한 — hang 방지용이며 성공 근거가 아니다. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /** 점유 갱신(heartbeat) 사이 시계 전진 폭(TTL 30s의 1/3). */
  private static final Duration HEARTBEAT_STEP = Duration.ofSeconds(10);

  private static final int EMBEDDING_DIM = 2;

  /** 수집기 행동. 본문은 람다 대신 이름으로 고른다. */
  enum Collector {
    SUCCEEDS,
    /** 수집 안에서 시계를 3초 전진시킨 뒤 성공한다 — 사이클의 시작 시각과 끝 시각이 다르다. */
    TAKES_THREE_SECONDS,
    /** 수집 안에서 시계를 6분 전진시킨 뒤 성공한다 — 끝날 때 15분 주기 소스가 due가 되어 있게. */
    TAKES_SIX_MINUTES,
    FAILS_FIRST_CALL,
    ALWAYS_FAILS,
    /** 첫 수집 안에서 시나리오가 닫힐 때까지 멈춘다(인터럽트로 깨지지 않음). 사이클이 끝나지 않은 상태. */
    HELD_IN_FIRST_COLLECTION,
    /** 실제 수집 런타임 + 첫 공급자 호출이 rate_limited(Retry-After 5분)라 재시도 대기에 들어간다. */
    WAITS_ON_RATE_LIMITED_PROVIDER,
    /** 실제 수집 런타임에서 점유를 쥔 채 커밋 없이 시나리오가 닫힐 때까지 멈춘다(교착 모사). */
    STUCK_HOLDING_CLAIM
  }

  /** 감독이 소스 목록을 읽을 때의 실패. */
  enum SupervisorStorage {
    WORKS,
    /** 사이클 수준 예외(IllegalStateException). */
    FAILS,
    /** 사이클이 삼키지 못하는 치명 오류(LinkageError). */
    FAILS_FATALLY
  }

  private final MutableClock clock = new MutableClock(T0);
  private final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  private final MemoryRunStore runs = new MemoryRunStore();
  private final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  private final MemoryProjection projection = new MemoryProjection(clock, sources, claims);
  private final CountedWorkers workers = CountedWorkers.of(2);

  private final BlockingQueue<CollectionDaemon.CycleOutcome> undelivered =
      new LinkedBlockingQueue<>();
  private final List<CollectionDaemon.CycleOutcome> outcomes = new CopyOnWriteArrayList<>();
  private final List<CollectionMode> modesSeen = new CopyOnWriteArrayList<>();
  private final AtomicInteger maintenanceRuns = new AtomicInteger();
  private final AtomicInteger collectCalls = new AtomicInteger();
  private final Map<SourceInstanceId, Collector> collectorsBySource = new ConcurrentHashMap<>();
  private final Map<SourceInstanceId, AtomicInteger> finishedCollections =
      new ConcurrentHashMap<>();
  private final HoldGate collectionHold = new HoldGate(WAIT_LIMIT);
  private final HoldGate observerHold = new HoldGate(WAIT_LIMIT);
  private final RateLimitedProvider provider = new RateLimitedProvider(clock);

  private Collector collector = Collector.SUCCEEDS;
  private SupervisorStorage storage = SupervisorStorage.WORKS;
  private boolean embedderWaitsOnProvider;
  private boolean maintenanceSweepFails;
  private boolean observerHolds;
  private Duration poll = ONE_CYCLE_POLL;
  private Duration shutdownTimeout = SHUTDOWN_TIMEOUT;
  private volatile Thread loopThread;
  private CollectionDaemon daemon;
  private CollectionRuntime runtime;

  // ---- 준비(given) ----

  SourceInstanceId givenSource(String id) {
    SourceInstanceId source = new SourceInstanceId(id);
    sources.save(SourceInstances.noop(source, T0));
    return source;
  }

  /** 그 시각에 시작해 성공한 run 기록 — 감독은 그 시각 + interval(15분)에 다시 due로 본다. */
  void givenSucceededAt(SourceInstanceId source, Instant startedAt) {
    runs.givenFinished(
        new RunId("seed-" + source.value()),
        source,
        RunStatus.SUCCEEDED,
        startedAt,
        startedAt.plusSeconds(1));
  }

  void givenProjectionRequested(SourceInstanceId source) {
    projection.outbox.requestProjection(source, T0);
  }

  /** 메시지 한 건과 투영 요청이 있는 Slack 소스. */
  SourceInstanceId givenSlackSourceAwaitingProjection(String id) {
    return projection.slackSourceWithOneMessage(id, "결제 장애 회고");
  }

  DaemonScenario collector(Collector behavior) {
    this.collector = behavior;
    return this;
  }

  /** 그 소스만 다른 수집기 행동을 쓴다(나머지는 collector(behavior)의 것). */
  DaemonScenario collector(SourceInstanceId source, Collector behavior) {
    collectorsBySource.put(source, behavior);
    return this;
  }

  DaemonScenario supervisorStorage(SupervisorStorage behavior) {
    this.storage = behavior;
    return this;
  }

  /** 투영의 임베딩 호출이 rate_limited로 재시도 대기에 들어간다. 임베더 포트는 토큰을 받지 않으므로 취소는 루프 스레드 인터럽트로만 온다. */
  DaemonScenario embedderWaitsOnRateLimitedProvider() {
    this.embedderWaitsOnProvider = true;
    return this;
  }

  /** 정비 정리(sweep)가 매번 실패한다 — 데몬에는 운영과 같은 DaemonMaintenance(실패를 로그로 삼킴)로 붙는다. */
  DaemonScenario maintenanceSweepFails() {
    this.maintenanceSweepFails = true;
    return this;
  }

  DaemonScenario polling(Duration poll) {
    this.poll = poll;
    return this;
  }

  DaemonScenario shutdownTimeout(Duration timeout) {
    this.shutdownTimeout = timeout;
    return this;
  }

  /** 관측자가 사이클 결과를 받은 뒤 releaseObserver()까지 루프를 붙잡는다 — 인터럽트로 깨지지 않는 작업(JDBC 소켓 읽기)의 모사. */
  DaemonScenario observerHoldsCycle() {
    this.observerHolds = true;
    return this;
  }

  /** 구성만 하고 시작하지 않은 데몬(수명 계약용). */
  CollectionDaemon daemon() {
    if (daemon == null) daemon = assemble();
    return daemon;
  }

  // ---- 실행(when) ----

  CollectionDaemon start() {
    daemon().start();
    return daemon;
  }

  /** 데몬 스레드에서 start한다 — 루프 스레드가 만든 스레드의 데몬 여부를 물려받지 않고 스스로 정하는지 보려고. */
  CollectionDaemon startFromDaemonThread() throws InterruptedException {
    Thread starter = new Thread(daemon()::start, "daemon-starter");
    starter.setDaemon(true);
    starter.start();
    starter.join(WAIT_LIMIT.toMillis());
    if (starter.isAlive()) throw new IllegalStateException("start가 돌아오지 않았다");
    return daemon;
  }

  void stop() {
    daemon.stop();
  }

  /** 루프 없이 이 스레드에서 틱 하나를 돌린다. 사이클 결과를 넘겼으면 true(루프는 그때만 poll을 기다린다). */
  boolean tickWithoutLoop() {
    return daemon().tick();
  }

  /** stop이 돌아오기까지 걸린 실제 시간. 하한 계약(타임아웃까지 기다린다)에만 쓴다. */
  Duration timedStop() {
    long started = System.nanoTime();
    daemon.stop();
    return Duration.ofNanos(System.nanoTime() - started);
  }

  /** 다른 스레드에서 stop을 부르고, 그 스레드가 합류를 기다리는 중에 인터럽트한다. */
  BackgroundThread stopAndInterruptCaller() throws InterruptedException {
    BackgroundThread stopper = BackgroundThread.start("stopper", daemon::stop);
    Eventually.await(
        "stop이 합류를 기다림", () -> stopper.isWaitingIn(CollectionDaemon.class, "stop"), WAIT_LIMIT);
    stopper.interruptAndJoin(WAIT_LIMIT);
    return stopper;
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  /** 그 소스의 작업 진행 신호(긴 백필 중의 페이지 커밋)를 남긴다. */
  void reportPageCommit(SourceInstanceId source) {
    projection.progress.mark(source);
  }

  /** 점유 갱신(heartbeat)만 계속 성공하도록 시계를 TTL/3씩 움직이며 스케줄러를 돌린다. */
  void runOnlyHeartbeatsFor(Duration span) {
    for (Duration elapsed = Duration.ZERO;
        elapsed.compareTo(span) < 0;
        elapsed = elapsed.plus(HEARTBEAT_STEP)) {
      clock.advance(HEARTBEAT_STEP);
      projection.scheduler.tick();
    }
  }

  void releaseObserver() {
    observerHold.release();
  }

  /** 데몬 밖에서, 정상 임베더로 due 투영을 한 번 더 돌린다. */
  List<ProjectionRun.Outcome> runDueProjectionsAgain() {
    return projection
        .projection(FixedEmbedder.zero(EMBEDDING_DIM), new SimpleMeterRegistry())
        .runDue(CancellationToken.none())
        .stream()
        .map(ProjectionRun.Report::outcome)
        .toList();
  }

  // ---- 대기(조건만, 상한은 hang 방지 — 넘으면 예외) ----

  /** 아직 받지 않은 다음 사이클 결과. */
  CollectionDaemon.CycleOutcome nextCycle() throws InterruptedException {
    CollectionDaemon.CycleOutcome outcome =
        undelivered.poll(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    if (outcome == null) throw new IllegalStateException("사이클 결과가 오지 않았다");
    return outcome;
  }

  void awaitFirstCycle() throws InterruptedException {
    nextCycle();
  }

  void awaitCycles(int n) throws InterruptedException {
    for (int i = 0; i < n; i++) nextCycle();
  }

  /** 지금 이후에 시작한 사이클 하나가 끝날 때까지. 이미 진행 중이던 사이클이 첫 결과로 올 수 있으므로 두 결과를 넘긴다. */
  void awaitCycleStartedAfterNow() throws InterruptedException {
    undelivered.clear();
    awaitCycles(2);
  }

  /** 수집기가 수집 안에 들어갔다(HELD_IN_FIRST_COLLECTION·STUCK_HOLDING_CLAIM). */
  void awaitCollectionEntered() {
    collectionHold.awaitHeld("수집 진입");
  }

  /** 그 소스의 수집이 (지금까지 끝난 것까지 합쳐) n번 끝났다. */
  void awaitCollectionsFinished(SourceInstanceId source, int n) {
    Eventually.await(
        source.value() + " 수집 " + n + "번 끝남", () -> finishedCollections(source) >= n, WAIT_LIMIT);
  }

  /** 수집 또는 투영이 공급자 호출에 들어갔다. */
  void awaitProviderCall() {
    provider.awaitCalled(WAIT_LIMIT);
  }

  /** 루프가 (치명 오류로) 끝날 때까지. */
  void awaitLoopEnded() throws InterruptedException {
    if (!daemon.awaitTermination(WAIT_LIMIT)) throw new IllegalStateException("루프가 끝나지 않았다");
  }

  boolean loopTerminated() throws InterruptedException {
    return daemon.awaitTermination(Duration.ZERO);
  }

  boolean loopTerminatesWithin(Duration d) throws InterruptedException {
    return daemon.awaitTermination(d);
  }

  /** 다른 스레드에서 awaitTermination()을 부르고 그 스레드가 대기에 들어갈 때까지 기다린다. */
  BackgroundThread waitForTerminationInBackground() {
    BackgroundThread waiter = BackgroundThread.start("await-termination", daemon::awaitTermination);
    Eventually.await("대기 스레드가 awaitTermination에 들어감", waiter::isBlockedOrDone, WAIT_LIMIT);
    return waiter;
  }

  // ---- 관찰(then) ----

  List<CollectionDaemon.CycleOutcome> outcomes() {
    return List.copyOf(outcomes);
  }

  List<CollectionMode> modesSeen() {
    return List.copyOf(modesSeen);
  }

  /** 그 소스의 수집이 끝난 횟수(붙잡힌 수집은 세지 않는다). */
  int finishedCollections(SourceInstanceId source) {
    AtomicInteger n = finishedCollections.get(source);
    return n == null ? 0 : n.get();
  }

  int maintenanceRuns() {
    return maintenanceRuns.get();
  }

  CollectionRun latestRun(SourceInstanceId source) {
    return runs.latest(source).orElseThrow();
  }

  boolean holdsActiveClaim(SourceInstanceId source) {
    return claims.current(source).filter(c -> c.isActiveAt(clock.instant())).isPresent();
  }

  int projectionRequestAttempts(SourceInstanceId source) {
    return projection.outbox.request(source).orElseThrow().attempts();
  }

  List<SourceInstanceId> pendingProjections() {
    return projection.outbox.pending();
  }

  boolean workersShutDown() {
    return workers.isShutdown();
  }

  /** 관측자 콜백을 부른 스레드 = 데몬의 루프 스레드. */
  Thread loopThread() {
    return loopThread;
  }

  /** 제품 조립(DaemonConfiguration)과 같은 상태 읽기로 본 health. 관측자 대신 시나리오가 받은 마지막 사이클을 쓴다. */
  Status health() {
    return new DaemonHealthIndicator(this::healthState, clock, STALL_AFTER).health().getStatus();
  }

  /** health가 읽는 데몬 상태(제품 DaemonState.of). 마지막 사이클은 시나리오가 받은 결과다. */
  DaemonHealthIndicator.DaemonState healthState() {
    Optional<CollectionDaemon.CycleOutcome> last =
        outcomes.isEmpty() ? Optional.empty() : Optional.of(outcomes.getLast());
    return DaemonHealthIndicator.DaemonState.of(daemon, last);
  }

  @Override
  public void close() {
    observerHold.release();
    collectionHold.release();
    if (daemon != null) daemon.stop();
    workers.shutdownNow();
  }

  // ---- 연결(테스트 본문에 보이지 않는다) ----

  private CollectionDaemon assemble() {
    SourceSupervisor supervisor =
        new SourceSupervisor(
            storage == SupervisorStorage.WORKS
                ? sources
                : SourceInstances.listing(sources, this::failListing),
            runs,
            claims,
            this::collect,
            new SourceSupervisor.Options(
                Duration.ofSeconds(900),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                2,
                Optional.empty()),
            clock,
            workers);
    return new CollectionDaemon(
        supervisor,
        projection.projection(
            embedderWaitsOnProvider
                ? provider.embedder(EMBEDDING_DIM)
                : FixedEmbedder.zero(EMBEDDING_DIM),
            new SimpleMeterRegistry()),
        maintenanceSweepFails
            ? new DaemonMaintenance(this::failMaintenanceSweep)
            : maintenanceRuns::incrementAndGet,
        new CollectionDaemon.Options(poll, MAINTENANCE_INTERVAL, shutdownTimeout),
        workers,
        clock,
        projection.progress,
        this::observe);
  }

  private CollectionRuntime.Report collect(
      SourceInstanceId source, CollectionMode mode, CancellationToken cancel) {
    modesSeen.add(mode);
    int call = collectCalls.incrementAndGet();
    return switch (collectorsBySource.getOrDefault(source, collector)) {
      case SUCCEEDS -> finishRun(source);
      case TAKES_THREE_SECONDS -> {
        clock.advance(Duration.ofSeconds(3));
        yield finishRun(source);
      }
      case TAKES_SIX_MINUTES -> {
        clock.advance(Duration.ofMinutes(6));
        yield finishRun(source);
      }
      case FAILS_FIRST_CALL -> {
        if (call == 1) throw new IllegalStateException(COLLECTOR_FAILURE);
        yield finishRun(source);
      }
      case ALWAYS_FAILS -> throw new IllegalStateException(COLLECTOR_FAILURE);
      case HELD_IN_FIRST_COLLECTION -> {
        collectionHold.hold();
        yield finishRun(source);
      }
      case WAITS_ON_RATE_LIMITED_PROVIDER, STUCK_HOLDING_CLAIM ->
          realRuntime().collect(source, mode, cancel);
    };
  }

  private void observe(CollectionDaemon.CycleOutcome outcome) {
    loopThread = Thread.currentThread();
    outcomes.add(outcome);
    undelivered.add(outcome);
    if (observerHolds) observerHold.hold();
  }

  private Maintenance.Report failMaintenanceSweep() {
    maintenanceRuns.incrementAndGet();
    throw new IllegalStateException("blob store unavailable");
  }

  private List<SourceInstance> failListing() {
    if (storage == SupervisorStorage.FAILS_FATALLY) throw new LinkageError("injected fatal error");
    throw new IllegalStateException(STORAGE_FAILURE);
  }

  /** run 기록을 남기고 그 보고를 돌려준다(감독이 다음 due를 계산하는 근거). */
  private CollectionRuntime.Report finishRun(SourceInstanceId source) {
    RunId run = new RunId("r-" + source.value() + "-" + collectCalls.get());
    CollectionRuntime.Report report =
        Reports.of(runs.recordSucceeded(run, source, clock.instant()));
    finishedCollections.computeIfAbsent(source, s -> new AtomicInteger()).incrementAndGet();
    return report;
  }

  private synchronized CollectionRuntime realRuntime() {
    if (runtime != null) return runtime;
    SourceAdapter adapter =
        collector == Collector.STUCK_HOLDING_CLAIM
            ? SourceInstances.adapter(input -> collectionHold.hold())
            : provider.adapter();
    runtime =
        MemoryCollectionRuntime.over(clock, sources, claims, runs)
            .rawItems(projection.rawItems)
            .outbox(projection.outbox)
            .progress(projection.progress)
            .heartbeats(projection.scheduler)
            .calls(provider.executor())
            .adapter(adapter)
            .build();
    return runtime;
  }
}
