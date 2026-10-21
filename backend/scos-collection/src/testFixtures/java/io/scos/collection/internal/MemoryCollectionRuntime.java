package io.scos.collection.internal;

import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceAdapter;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestMetrics;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 메모리 포트 위의 실제 수집 런타임 조립(테스트 공용): 점유·run 기록·페이지 커밋·완료·heartbeat·취소 경로가 제품 코드다. 호출자는 함께 보는
 * 포트(소스·점유·run, 필요하면 원본·요청·진행·스케줄러)만 넘기고 나머지는 새 메모리 구현이다.
 */
public final class MemoryCollectionRuntime {
  public static final Duration LEASE_TTL = Duration.ofSeconds(30);

  /** 공급자 재시도 대기를 실제로 기다리지 않는다(취소만 본다) — PostgresCollectionRuntime과 같은 기본. */
  private static final ProviderCallExecutor.Waiter NO_WAIT =
      (delay, cancel) -> cancel.isCancelled();

  private final MutableClock clock;
  private final SourceInstanceStore sources;
  private final WorkClaimPort claims;
  private final RunStore runs;
  private RawItemStore rawItems = new MemoryRawItemStore();
  private OutboxPort outbox = new MemoryOutboxPort();
  private CheckpointPort checkpoints = new MemoryCheckpointPort();
  private SnapshotGuardStore guards = new MemorySnapshotGuardStore();
  private Progress progress;
  private TaskScheduler heartbeats = new ManualTaskScheduler();
  private SourceAdapter adapter = SourceInstances.NOOP_ADAPTER;
  private ProviderCallExecutor calls; // 없으면 build()가 지표 레지스트리로 만든다
  private CollectionMetrics metrics = TestMetrics.collection();
  private Supplier<RunId> runIds = () -> new RunId("run-1");

  private MemoryCollectionRuntime(
      MutableClock clock, SourceInstanceStore sources, WorkClaimPort claims, RunStore runs) {
    this.clock = clock;
    this.sources = sources;
    this.claims = claims;
    this.runs = runs;
    this.progress = new Progress(clock);
  }

  public static MemoryCollectionRuntime over(
      MutableClock clock, SourceInstanceStore sources, WorkClaimPort claims, RunStore runs) {
    return new MemoryCollectionRuntime(clock, sources, claims, runs);
  }

  public MemoryCollectionRuntime rawItems(RawItemStore rawItems) {
    this.rawItems = rawItems;
    return this;
  }

  public MemoryCollectionRuntime outbox(OutboxPort outbox) {
    this.outbox = outbox;
    return this;
  }

  public MemoryCollectionRuntime checkpoints(CheckpointPort checkpoints) {
    this.checkpoints = checkpoints;
    return this;
  }

  /** 스냅샷 축소 보호 상태를 함께 보는 테스트가 넘긴다. */
  public MemoryCollectionRuntime guards(SnapshotGuardStore guards) {
    this.guards = guards;
    return this;
  }

  public MemoryCollectionRuntime progress(Progress progress) {
    this.progress = progress;
    return this;
  }

  public MemoryCollectionRuntime heartbeats(TaskScheduler scheduler) {
    this.heartbeats = scheduler;
    return this;
  }

  /** 이 런타임이 걷는 수집 어댑터(kind 하나 — 기본은 noop). */
  public MemoryCollectionRuntime adapter(SourceAdapter adapter) {
    this.adapter = adapter;
    return this;
  }

  /** 공급자 호출 실행기를 직접 준다(공급자 대역의 각본·대기 기록). metrics(…)와 순서에 상관없이 이것을 쓴다. */
  public MemoryCollectionRuntime calls(ProviderCallExecutor calls) {
    this.calls = calls;
    return this;
  }

  /** 지표를 단언하는 테스트가 레지스트리를 쥔 지표를 넘긴다(calls를 주지 않으면 공급자 호출 지표도 이 레지스트리). */
  public MemoryCollectionRuntime metrics(CollectionMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  /** 실행마다 다른 run id가 필요할 때(같은 런타임으로 여러 번 수집해 run별 관측을 보는 테스트). 기본은 늘 "run-1". */
  public MemoryCollectionRuntime runIds(Supplier<RunId> runIds) {
    this.runIds = runIds;
    return this;
  }

  public CollectionRuntime build() {
    MemorySourceChangeLog changes = new MemorySourceChangeLog();
    TransactionOperations tx = TransactionOperations.withoutTransaction();
    return new CollectionRuntime(
        sources,
        SourceAdapters.of(List.of(adapter)),
        RawContentCapture.defaults(),
        calls != null
            ? calls
            : new ProviderCallExecutor(
                ProviderCallPolicy.defaults(() -> 0.0),
                clock,
                NO_WAIT,
                ProviderCallMetrics.of(metrics.registry())),
        claims,
        checkpoints,
        runs,
        new PageCommit(
            tx, claims, runs, rawItems, checkpoints, outbox, clock, metrics, changes, progress),
        new RunCompletion(
            tx,
            claims,
            runs,
            rawItems,
            outbox,
            changes,
            new SnapshotShrinkGuard(sources, guards, SnapshotShrinkLimits.DEFAULTS, metrics)),
        new OwnerId("memory-runtime"),
        new LeaseHeartbeats(claims, LEASE_TTL, clock, heartbeats, metrics),
        clock,
        runIds,
        metrics);
  }
}
