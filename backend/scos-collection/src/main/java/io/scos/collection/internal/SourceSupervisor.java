package io.scos.collection.internal;

import io.scos.engine.support.text.EventLog;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 감독(F11, TS SourceSupervisor): enabled 소스를 id 순으로 due 판정 → 빈 worker(최대 concurrency)가 due인 소스를 id
 * 순으로 집어 수집 → 소스별 격리(예외는 error 시도). worker는 시도를 마치면 **그 순간 다시 판정해** 다음 due 소스를 집고, 집을 것이 없으면 끝난다.
 * 사이클은 장벽이 아니다(ADR-0020 §1 "사이클"): 사이클은 시작 시점에 due였던 소스가 전부 시도를 마치면 끝나지만, 그동안에도 틱(데몬 poll)마다 새로
 * due가 된 소스를 빈 worker에 준다 — 긴 소스(Drive·Notion 스냅샷)가 worker 하나를 몇 시간 쥐어도 15분 주기 소스는 밀리지 않는다. 같은 소스는
 * 이 프로세스에서 동시에 두 번 집지 않는다(수집 중 표시, 판정과 함께 lock 안), 프로세스 사이는 점유(WorkClaimPort)가 막는다. due: 점유가 live면
 * 만료 시각, 마지막 시도가 없으면 즉시, 마지막 run이 failed·partially_succeeded·cancelled·running이면 min(retryMax,
 * retryBase·2^(max(0, 최근 실패 수−1))), 성공이면 interval(소스별 값이 있으면 그것, ADR-0022 §3). 스냅샷 모드는 마지막 complete
 * snapshot 완료·마지막 스냅샷 시도(메모리) 중 늦은 시각 + 간격(소스별 값 → 데몬 값)이 지났을 때(둘 다 없으면 즉시). 이벤트 처리 단계는 없다(폴링만,
 * ADR-0008). 순수 정책 — 실행기와 포트만 안다(ADR-0020 §1).
 */
public final class SourceSupervisor {
  private static final Logger log = LoggerFactory.getLogger(SourceSupervisor.class);

  /** 감독 설정(TS --interval-seconds 등). retryBase ≤ retryMax, 전부 양수. */
  public record Options(
      Duration interval,
      Duration retryBase,
      Duration retryMax,
      int concurrency,
      Optional<Duration> snapshotInterval) {
    public Options {
      if (interval == null || interval.isZero() || interval.isNegative())
        throw new IllegalArgumentException("interval must be positive");
      if (retryBase == null || retryBase.isZero() || retryBase.isNegative())
        throw new IllegalArgumentException("retryBase must be positive");
      if (retryMax == null || retryMax.compareTo(retryBase) < 0)
        throw new IllegalArgumentException("retryMax must be >= retryBase");
      if (concurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1");
      snapshotInterval = snapshotInterval == null ? Optional.empty() : snapshotInterval;
      if (snapshotInterval.filter(d -> d.isZero() || d.isNegative()).isPresent())
        throw new IllegalArgumentException("snapshotInterval must be positive");
    }
  }

  /** 수집 호출(런타임). */
  @FunctionalInterface
  public interface Collector {
    CollectionRuntime.Report collect(
        SourceInstanceId source, CollectionMode mode, CancellationToken cancel);
  }

  /** 한 소스 시도의 결과: run 보고, 다른 인스턴스가 수집 중(점유 경합), 또는 run이 되지 못한 예외(원인 보존). */
  public sealed interface AttemptOutcome {
    record Collected(CollectionRuntime.Report report) implements AttemptOutcome {}

    record AlreadyRunning() implements AttemptOutcome {}

    record Failed(RuntimeException cause) implements AttemptOutcome {}
  }

  /** 한 소스의 시도. 실패 = run의 오류(errorCount > 0)·점유 경합·예외(지표 scos.supervisor.attempts{failed}). */
  public record Attempt(SourceInstanceId source, CollectionMode mode, AttemptOutcome outcome) {
    public boolean failed() {
      return switch (outcome) {
        case AttemptOutcome.Collected c -> c.report().errorCount() > 0;
        case AttemptOutcome.AlreadyRunning a -> true;
        case AttemptOutcome.Failed f -> true;
      };
    }
  }

  /**
   * 한 사이클: 그동안 끝난 시도(집은 순서 — 한 판정 안에서는 id 순, 앞 사이클에 시작해 이번에 끝난 시도와 사이클 도중 다시 due가 된 소스의 시도도 포함)와 시작
   * 시점에 유예된 소스의 다음 자격 시각. 끝나지 않은 시도는 끝나는 사이클에 실린다.
   */
  public record Cycle(
      Instant startedAt,
      Instant finishedAt,
      List<Attempt> attempts,
      Map<SourceInstanceId, Instant> deferred) {}

  private final SourceInstanceStore sources;
  private final RunStore runs;
  private final WorkClaimPort claims;
  private final Collector collector;
  private final Options options;
  private final Clock clock;
  private final ExecutorService workers;

  /** 프로세스 메모리에만 — 재기동 시 한 번의 즉시 재시도는 허용(TS와 같다). */
  private final Map<SourceInstanceId, Instant> lastSnapshotAttemptAt = new ConcurrentHashMap<>();

  /** 취소 확인 간격 — 토큰 취소는 기다리는 틱을 깨우지 않는다. */
  private static final Duration CANCEL_CHECK = Duration.ofMillis(100);

  /**
   * 아래 상태(inFlight 쓰기·activeWorkers·open·finished·sequence)를 지킨다. 판정(저장소 읽기)도 이 안에서 한다 — 같은 소스를 두
   * worker가 집지 않게.
   */
  private final Object lock = new Object();

  /** 수집 중인 소스 → 시도 시작 시각. 쓰기는 lock 안, 읽기(health)는 어디서나. */
  private final Map<SourceInstanceId, Instant> inFlight = new ConcurrentHashMap<>();

  private final List<Finished> finished = new ArrayList<>();

  /**
   * 지난 틱 뒤에 시도를 마친 소스 — 다음 틱까지 다시 집지 않는다. run이 되지 못한 실패(예외·점유 경합)는 run 기록이 없어 곧바로 다시 due라, 빈
   * worker가 바로 다시 집으면 공회전한다. 장벽 시절처럼 다음 기회는 다음 틱(poll 뒤)이다.
   */
  private final Set<SourceInstanceId> attemptedSinceTick = new HashSet<>();

  private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
  private int activeWorkers;
  private long sequence;
  private OpenCycle open;

  private record Evaluation(List<SourceInstance> due, Map<SourceInstanceId, Instant> deferred) {}

  private record Picked(SourceInstanceId source, CollectionMode mode, long sequence) {}

  /** 끝난 시도와 집은 순서(사이클 결과는 이 순서 — 한 판정 안에서는 id 순). */
  private record Finished(long sequence, Attempt attempt) {}

  /** 열린 사이클: 시작 시각, 그때 due였고 아직 시도를 마치지 않은 소스, 그때의 유예. */
  private static final class OpenCycle {
    final Instant startedAt;
    final Set<SourceInstanceId> pending = new LinkedHashSet<>();
    final Map<SourceInstanceId, Instant> deferred;

    OpenCycle(
        Instant startedAt, List<SourceInstance> due, Map<SourceInstanceId, Instant> deferred) {
      this.startedAt = startedAt;
      due.forEach(instance -> pending.add(instance.id()));
      this.deferred = deferred;
    }

    /** 다시 판정해 due도 아니고 수집 중도 아닌 소스(다른 인스턴스가 가져갔다·꺼졌다)는 기다리지 않는다. */
    void keepOnlyPending(Evaluation evaluation, Set<SourceInstanceId> running) {
      Set<SourceInstanceId> due = new HashSet<>();
      evaluation.due().forEach(instance -> due.add(instance.id()));
      pending.removeIf(id -> !due.contains(id) && !running.contains(id));
    }
  }

  public SourceSupervisor(
      SourceInstanceStore sources,
      RunStore runs,
      WorkClaimPort claims,
      Collector collector,
      Options options,
      Clock clock,
      ExecutorService workers) {
    this.sources = sources;
    this.runs = runs;
    this.claims = claims;
    this.collector = collector;
    this.options = options;
    this.clock = clock;
    this.workers = workers;
  }

  /** 이 프로세스에서 지금 수집 중인 소스와 그 시도의 시작 시각(health가 시도별 진행을 본다). */
  public Map<SourceInstanceId, Instant> running() {
    return Map.copyOf(inFlight);
  }

  /**
   * 틱 하나: 지금 due인(이 프로세스에서 수집 중이 아닌) 소스 수만큼 빈 worker를 띄우고, 열린 사이클이 끝나기를 최대 wait만큼 기다린다. 사이클이
   * 끝났으면(또는 취소·호출 스레드 인터럽트로 닫혔으면) 그 결과, 아니면 비어 있다 — 그동안에도 수집은 계속되고, 다음 틱이 그 사이 due가 된 소스를 빈 worker에
   * 준다. 대기 상한 없는 호출은 두지 않는다 — 사이클이 끝나지 않으면 호출 스레드가 영영 돌아오지 않는다.
   */
  public Optional<Cycle> runDueSources(CancellationToken cancel, Duration wait) {
    rethrowWorkerFailure();
    int toStart;
    synchronized (lock) {
      attemptedSinceTick.clear();
      Instant now = clock.instant();
      Evaluation evaluation = evaluate(now);
      if (open == null) open = new OpenCycle(now, evaluation.due(), evaluation.deferred());
      else open.keepOnlyPending(evaluation, inFlight.keySet());
      toStart =
          cancel.isCancelled()
              ? 0
              : Math.min(options.concurrency() - activeWorkers, evaluation.due().size());
      activeWorkers += toStart;
    }
    for (int i = 0; i < toStart; i++) {
      try {
        workers.execute(() -> work(cancel));
      } catch (RejectedExecutionException e) {
        // 종료 중(stop이 실행기를 닫았다) — 띄우지 못한 worker를 되돌린다
        synchronized (lock) {
          activeWorkers--;
          lock.notifyAll();
        }
      }
    }
    return awaitCycleEnd(cancel, wait);
  }

  /**
   * 열린 사이클의 시작 시점 due 소스가 전부 시도를 마칠 때까지(또는 인터럽트·상한까지) 기다린다. 취소되면 남은 due 소스는 집지 않으므로, 수집 중인 시도가 마감될
   * 때까지만 기다렸다 닫는다 — 시도 안에서 취소돼도 그 시도는 이 사이클에 실린다. 그동안 상한이 지나면 끝난 시도만으로 닫는다.
   */
  private Optional<Cycle> awaitCycleEnd(CancellationToken cancel, Duration wait) {
    long deadline = System.nanoTime() + wait.toNanos();
    synchronized (lock) {
      try {
        while (cancel.isCancelled() ? !inFlight.isEmpty() : !open.pending.isEmpty()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            if (cancel.isCancelled()) break;
            return Optional.empty();
          }
          // 취소는 토큰만 바꾸고 이 모니터를 깨우지 않는다 — 기다림을 잘게 나눠 토큰을 다시 본다
          TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, CANCEL_CHECK.toNanos()));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      List<Attempt> attempts =
          finished.stream()
              .sorted(Comparator.comparingLong(Finished::sequence))
              .map(Finished::attempt)
              .toList();
      finished.clear();
      Cycle cycle = new Cycle(open.startedAt, clock.instant(), attempts, Map.copyOf(open.deferred));
      open = null;
      return Optional.of(cycle);
    }
  }

  /** worker 하나: 비면 그 순간 due를 다시 판정해 다음 소스를 집고, 집을 것이 없으면 끝난다. */
  private void work(CancellationToken cancel) {
    try {
      Optional<Picked> next = pick(cancel);
      while (next.isPresent()) {
        Picked p = next.get();
        Attempt result = null;
        try {
          result = attempt(p.source(), p.mode(), cancel);
        } finally {
          next = finish(p, result, cancel);
        }
      }
    } catch (RuntimeException | Error e) {
      // 판정(저장소)·실행기 수준 실패 — 다음 틱의 호출 스레드에 던진다(시도별 예외는 attempt가 이미 시도로 바꿨다)
      workerFailure.compareAndSet(null, e);
      synchronized (lock) {
        activeWorkers--;
        lock.notifyAll();
      }
    }
  }

  /** 다음 소스를 집는다. 없으면 이 worker는 끝난 것으로 센다(판정과 퇴장이 한 임계 구역 — 틱이 빈 자리를 정확히 본다). */
  private Optional<Picked> pick(CancellationToken cancel) {
    synchronized (lock) {
      Optional<Picked> picked = Optional.empty();
      if (!cancel.isCancelled()) {
        Instant now = clock.instant();
        Evaluation evaluation = evaluate(now);
        if (open != null) open.keepOnlyPending(evaluation, inFlight.keySet());
        if (!evaluation.due().isEmpty()) {
          SourceInstance instance = evaluation.due().getFirst();
          CollectionMode mode = resolveMode(instance, now);
          if (mode == CollectionMode.SNAPSHOT) lastSnapshotAttemptAt.put(instance.id(), now);
          inFlight.put(instance.id(), now);
          picked = Optional.of(new Picked(instance.id(), mode, sequence++));
        }
      }
      if (picked.isEmpty()) {
        activeWorkers--;
        lock.notifyAll();
      }
      return picked;
    }
  }

  /** 시도를 마감하고(결과가 없으면 Error로 끝난 시도 — 싣지 않는다) 같은 임계 구역에서 다음 소스를 집는다. */
  private Optional<Picked> finish(Picked p, Attempt result, CancellationToken cancel) {
    synchronized (lock) {
      inFlight.remove(p.source());
      attemptedSinceTick.add(p.source());
      if (result != null) finished.add(new Finished(p.sequence(), result));
      if (open != null) open.pending.remove(p.source());
      lock.notifyAll();
      return result == null ? Optional.empty() : pick(cancel);
    }
  }

  private void rethrowWorkerFailure() {
    Throwable failure = workerFailure.getAndSet(null);
    if (failure != null) throw new IllegalStateException("supervisor worker failed", failure);
  }

  /** enabled 소스(id 순)를 지금 시각으로 판정한다. 이 프로세스에서 수집 중이거나 지난 틱 뒤에 시도를 마친 소스는 due도 유예도 아니다. */
  private Evaluation evaluate(Instant now) {
    List<SourceInstance> due = new ArrayList<>();
    Map<SourceInstanceId, Instant> deferred = new LinkedHashMap<>();
    List<SourceInstance> enabled =
        sources.listEnabled().stream()
            .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
            .toList();
    for (SourceInstance instance : enabled) {
      if (inFlight.containsKey(instance.id()) || attemptedSinceTick.contains(instance.id()))
        continue;
      Optional<Instant> eligibleAt = nextEligibleAt(instance, now);
      if (eligibleAt.filter(t -> t.isAfter(now)).isPresent())
        deferred.put(instance.id(), eligibleAt.get());
      else due.add(instance);
    }
    return new Evaluation(due, deferred);
  }

  private Attempt attempt(SourceInstanceId id, CollectionMode mode, CancellationToken cancel) {
    if (mode == CollectionMode.SNAPSHOT)
      EventLog.info(log, "collection.run.snapshot").source(id).log();
    try {
      return new Attempt(
          id, mode, new AttemptOutcome.Collected(collector.collect(id, mode, cancel)));
    } catch (AlreadyRunningException e) {
      return new Attempt(id, mode, new AttemptOutcome.AlreadyRunning());
    } catch (RuntimeException e) {
      // run이 되지 못한 실패(설정 드리프트·점유 전 저장소 오류 등)는 run 기록에 남지 않는다 — 원인 스택을 여기서 남긴다
      EventLog.error(log, "supervisor.source.error")
          .source(id)
          .detail(EventLog.detail("message", errorMessage(e)))
          .cause(e)
          .log();
      return new Attempt(id, mode, new AttemptOutcome.Failed(e));
    }
  }

  /** 다음 자격 시각. 비어 있으면 지금 due. */
  Optional<Instant> nextEligibleAt(SourceInstance instance, Instant now) {
    SourceInstanceId id = instance.id();
    Optional<WorkClaim> claim = claims.current(id).filter(c -> c.isActiveAt(now));
    if (claim.isPresent()) return Optional.of(claim.get().expiresAt());
    Optional<CollectionRun> last = runs.latest(id);
    if (last.isEmpty()) return Optional.empty();
    RunStatus status = last.get().status();
    boolean retryable =
        status == RunStatus.FAILED
            || status == RunStatus.PARTIALLY_SUCCEEDED
            || status == RunStatus.CANCELLED
            || status == RunStatus.RUNNING;
    Duration delay =
        retryable
            ? retryDelay(runs.recentFailureCount(id))
            : instance.schedule().collectIntervalOr(options.interval());
    return Optional.of(last.get().startedAt().plus(delay));
  }

  /** min(retryMax, retryBase·2^(max(0, failures−1))). */
  Duration retryDelay(int recentFailures) {
    int exponent = Math.min(30, Math.max(0, recentFailures - 1));
    Duration grown = options.retryBase().multipliedBy(1L << exponent);
    return grown.compareTo(options.retryMax()) > 0 ? options.retryMax() : grown;
  }

  CollectionMode resolveMode(SourceInstance instance, Instant now) {
    SourceInstanceId id = instance.id();
    Optional<Duration> interval =
        instance.schedule().snapshotIntervalOr(options.snapshotInterval());
    if (interval.isEmpty()) return CollectionMode.INCREMENTAL;
    Optional<Instant> completed =
        runs.lastSnapshotCompletion(id).map(RunStore.SnapshotCompletion::completedAt);
    Optional<Instant> attempted = Optional.ofNullable(lastSnapshotAttemptAt.get(id));
    if (completed.isEmpty() && attempted.isEmpty()) return CollectionMode.SNAPSHOT;
    Instant last =
        completed.map(c -> attempted.filter(a -> a.isAfter(c)).orElse(c)).orElseGet(attempted::get);
    return last.plus(interval.get()).isAfter(now)
        ? CollectionMode.INCREMENTAL
        : CollectionMode.SNAPSHOT;
  }

  /**
   * TS `error instanceof Error ? error.message : "Source collection failed"`: 메시지가 없으면 TS 대체 문구.
   */
  static String errorMessage(RuntimeException e) {
    return e.getMessage() == null ? "Source collection failed" : e.getMessage();
  }
}
