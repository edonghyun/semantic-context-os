package io.scos.server;

import io.scos.collection.internal.Progress;
import io.scos.collection.internal.SourceSupervisor;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.spi.connector.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.SmartLifecycle;

/**
 * 상주 데몬(ADR-0008·ADR-0020 §1): 스레드 하나가 틱(감독 틱 → due 투영 → 유지보수(due면))을 반복한다. 감독 틱은 새로 due가 된 소스를 빈
 * worker에 주고 열린 사이클의 끝을 최대 poll 기다린다 — 긴 수집이 도는 동안에도 poll마다 다시 판정한다(사이클은 장벽이 아니다). 사이클이 끝난 틱 뒤에만
 * poll 대기를 한다. 사이클 결과(관측자·cycles·lastCycleAt·readiness)는 사이클이 끝날 때 한 번이다. 대기는 취소 토큰으로 즉시
 * 깨어난다(Thread.sleep 아님). 종료(stop): 토큰 취소 → worker 실행기 shutdownNow와 루프 스레드 인터럽트(진행 중 공급자 호출·재시도 대기가
 * 수집·투영 모두에서 cancelled로 끝난다) → worker·루프 합류(각각 상한 shutdownTimeout). 진행 중 run의 cancelled 기록·점유 해제는
 * 런타임의 F6 경로가, 투영의 CANCELLED(요청 보존)·점유 해제는 ProjectionRun이 한다. phase 최대 — 가장 먼저 멈추고 가장 나중에 시작한다. 한 번
 * 멈춘 데몬은 다시 시작하지 않는다(명령 프로세스 하나에 데몬 하나).
 */
public final class CollectionDaemon implements SmartLifecycle, ExitCodeGenerator {
  /** 루프가 취소 없이 치명 오류로 끝났을 때의 프로세스 종료 코드(EX_SOFTWARE) — 감시자(systemd·k8s)가 실패로 보고 다시 띄운다. */
  public static final int LOOP_DIED_EXIT = 70;

  private static final Logger log = LoggerFactory.getLogger(CollectionDaemon.class);

  /** 루프 설정: 사이클 사이 대기, 유지보수 간격, 종료 합류 상한. 전부 양수. */
  public record Options(Duration poll, Duration maintenanceInterval, Duration shutdownTimeout) {
    public Options {
      requirePositive("poll", poll);
      requirePositive("maintenanceInterval", maintenanceInterval);
      requirePositive("shutdownTimeout", shutdownTimeout);
    }

    private static void requirePositive(String name, Duration value) {
      if (value == null || value.isZero() || value.isNegative())
        throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * 한 사이클의 관측 결과(지표·health가 읽는다). collection은 감독 사이클이 끝까지 돌았을 때만 있다 — 사이클 예외(error)면 없다.
   * projections는 앞 결과 뒤로 모인 틱들의 투영 보고다.
   */
  public record CycleOutcome(
      Instant finishedAt,
      Optional<SourceSupervisor.Cycle> collection,
      List<ProjectionRun.Report> projections,
      Optional<RuntimeException> error) {}

  private final SourceSupervisor supervisor;
  private final ProjectionRun projection;
  private final Runnable maintenance;
  private final Options options;
  private final ExecutorService workers;
  private final Clock clock;
  private final Progress progress;
  private final Consumer<CycleOutcome> observer;

  private final CancellationToken cancel = new CancellationToken();
  private final CountDownLatch terminated = new CountDownLatch(1);
  private final AtomicLong cycles = new AtomicLong();
  private final AtomicReference<Instant> lastCycleAt = new AtomicReference<>();
  private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();
  private final List<ProjectionRun.Report> projections = new ArrayList<>();
  private final AtomicReference<Instant> lastMaintenanceAt = new AtomicReference<>();
  private final AtomicReference<Throwable> fatal = new AtomicReference<>();
  private final AtomicReference<Instant> startedAt = new AtomicReference<>();
  private volatile Thread loop;
  private volatile boolean running;

  public CollectionDaemon(
      SourceSupervisor supervisor,
      ProjectionRun projection,
      Runnable maintenance,
      Options options,
      ExecutorService workers,
      Clock clock,
      Progress progress,
      Consumer<CycleOutcome> observer) {
    this.progress = progress;
    this.supervisor = supervisor;
    this.projection = projection;
    this.maintenance = maintenance;
    this.options = options;
    this.workers = workers;
    this.clock = clock;
    this.observer = observer;
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    startedAt.set(clock.instant());
    Thread t = new Thread(this::run, "scos-supervisor");
    t.setDaemon(false);
    loop = t;
    t.start();
    log.info(
        "daemon started: poll={} shutdownTimeout={}", options.poll(), options.shutdownTimeout());
  }

  private void run() {
    try {
      while (!cancel.isCancelled()) {
        // 사이클이 열려 있으면 감독 틱이 이미 poll만큼 기다렸다 — 바로 다음 틱에서 그 사이 due가 된 소스를 빈 worker에 준다
        if (tick() && cancel.awaitCancellation(options.poll())) break;
      }
    } catch (Throwable t) {
      // 사이클은 RuntimeException을 삼키고 계속한다 — 여기 오는 것은 Error(OOM·링크 오류 등). 루프는 끝나고 프로세스는 비0으로 끝난다
      fatal.set(t);
      log.error("daemon loop died — the process will exit with {}", LOOP_DIED_EXIT, t);
    } finally {
      terminated.countDown();
      log.info("daemon loop finished after {} cycle(s)", cycles.get());
    }
  }

  /**
   * 틱 하나. 감독 사이클이 끝났거나 틱이 예외로 끝났으면 사이클 결과를 넘기고 true. 틱 안의 예외는 다음 틱을 막지 않는다(소스별 격리는 감독이, 나머지는 여기서).
   * 투영·유지보수는 진행 중인 수집과 겹칠 수 있다 — 다른 인스턴스·일회성 명령과 겹치는 것과 같은 경우다(투영은 소스별 점유가, 유지보수는 유예 기간이 진행 중 run의
   * blob·변경 로그를 지킨다).
   */
  boolean tick() {
    Optional<SourceSupervisor.Cycle> collection = Optional.empty();
    Optional<RuntimeException> failure = Optional.empty();
    try {
      collection = supervisor.runDueSources(cancel, options.poll());
      if (!cancel.isCancelled()) projections.addAll(projection.runDue(cancel));
      if (!cancel.isCancelled()) runMaintenanceIfDue();
    } catch (RuntimeException e) {
      failure = Optional.of(e);
      log.error("daemon cycle failed", e);
    }
    lastTickAt.set(clock.instant());
    if (collection.isEmpty() && failure.isEmpty()) return false;
    List<ProjectionRun.Report> reported = List.copyOf(projections);
    projections.clear();
    cycles.incrementAndGet();
    lastCycleAt.set(clock.instant());
    observer.accept(new CycleOutcome(clock.instant(), collection, reported, failure));
    return true;
  }

  private void runMaintenanceIfDue() {
    Instant now = clock.instant();
    Instant last = lastMaintenanceAt.get();
    if (last != null && last.plus(options.maintenanceInterval()).isAfter(now)) return;
    maintenance.run();
    lastMaintenanceAt.set(now);
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  /**
   * 취소 → worker·루프 인터럽트 → 합류(상한). 상한 안에 끝나지 않은 작업은 기록하고 넘어간다(DataSource 종료가 실패시킨다, ADR-0020 §1).
   * worker 실행기는 감독과 이 데몬이 함께 쓰고, 종료는 이 데몬이 소유한다(빈 destroy는 비상용).
   */
  @Override
  public synchronized void stop() {
    if (!running) return;
    running = false;
    cancel.cancel();
    workers.shutdownNow();
    Thread t = loop;
    // 루프 스레드의 투영·유지보수는 토큰을 끝까지 받지 못한다(임베더 포트) — 인터럽트로 공급자 대기를 깨운다
    if (t != null) t.interrupt();
    Duration timeout = options.shutdownTimeout();
    try {
      boolean workersDone = workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (t != null) t.join(timeout.toMillis());
      if (!workersDone || (t != null && t.isAlive()))
        log.warn("daemon stop exceeded {} — some work did not finish", timeout);
      else log.info("daemon stopped cleanly");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  /** 루프가 끝날 때까지 기다린다(명령 진입점이 프로세스를 살려 두는 데 쓴다). */
  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  /** 최대 timeout만큼 루프 종료를 기다린다. 끝났으면 true. */
  public boolean awaitTermination(Duration timeout) throws InterruptedException {
    return terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public long cycles() {
    return cycles.get();
  }

  public Optional<Instant> lastCycleAt() {
    return Optional.ofNullable(lastCycleAt.get());
  }

  /** 루프 스레드가 아직 돈다(시작됐고, 멈추지도 죽지도 않았다). */
  public boolean loopAlive() {
    return running && terminated.getCount() > 0;
  }

  /**
   * 마지막 진행 시각 — health의 정체 판정 기준. 루프의 진행(시작, 틱 끝, 작업 진행 신호(페이지 커밋·투영 문서 쓰기) 중 가장 늦은 것)과 수집 중인 시도마다의
   * 진행(시도 시작, 그 소스의 페이지 커밋 중 늦은 것) 가운데 **가장 이른 것**이다. 사이클이 장벽이 아니므로 다른 소스의 시도·커밋이 이어져도 멈춘 worker
   * 하나가 가려지지 않는다. 점유 갱신(heartbeat)은 넣지 않는다(교착된 worker를 가리지 않게).
   */
  public Optional<Instant> lastProgressAt() {
    Optional<Instant> loop =
        Stream.of(
                Optional.ofNullable(startedAt.get()),
                Optional.ofNullable(lastTickAt.get()),
                progress.lastAt())
            .flatMap(Optional::stream)
            .max(Instant::compareTo);
    Stream<Instant> attempts =
        supervisor.running().entrySet().stream()
            .map(
                e ->
                    progress
                        .lastAt(e.getKey())
                        .filter(t -> t.isAfter(e.getValue()))
                        .orElse(e.getValue()));
    return Stream.concat(loop.stream(), attempts).min(Instant::compareTo);
  }

  /** 루프를 죽인 치명 오류. */
  public Optional<Throwable> fatalError() {
    return Optional.ofNullable(fatal.get());
  }

  /** 루프가 치명 오류로 끝났으면 {@link #LOOP_DIED_EXIT}(명령 진입점의 종료 코드). */
  @Override
  public int getExitCode() {
    return fatal.get() == null ? 0 : LOOP_DIED_EXIT;
  }

  /** 첫 사이클이 끝났는가 — readiness. */
  public boolean ready() {
    return cycles.get() > 0;
  }
}
