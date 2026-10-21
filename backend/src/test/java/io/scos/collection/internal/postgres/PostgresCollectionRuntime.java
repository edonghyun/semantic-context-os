package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.SourceAdapters;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.connector.SourceAdapter;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 어댑터 위의 실제 수집 런타임 조립(DB 종단 테스트·자식 JVM 공용). 메모리 판은 `MemoryCollectionRuntime`. 커넥터는 자기
 * 어댑터 하나만 넘긴다 — 점유·run 기록·페이지 커밋·완료·heartbeat는 제품 부품이고 제품 조립(CollectionConfiguration)과 같다.
 *
 * <pre>
 * CollectionRuntime runtime =
 *     PostgresCollectionRuntime.over(dataSource, clock)
 *         .adapter(JiraAssembly.adapter(server.baseUrl(), token, clock))
 *         .owner("w1")
 *         .build();
 * </pre>
 */
public final class PostgresCollectionRuntime {
  public static final Duration LEASE_TTL = Duration.ofSeconds(30);

  /** 공급자 재시도 대기를 실제로 기다리지 않는다(취소만 본다). 실제 대기가 필요한 프로브는 {@link #waitsForReal()}. */
  private static final ProviderCallExecutor.Waiter NO_WAIT =
      (delay, cancel) -> cancel.isCancelled();

  private final DataSource dataSource;
  private final Clock clock;
  private SourceAdapter adapter;
  private String owner = "w1";
  private Duration leaseTtl = LEASE_TTL;
  private CheckpointPort checkpointReads;
  private Supplier<RunId> runIds;
  private TaskScheduler heartbeats = new ManualTaskScheduler();
  private ProviderCallExecutor.Waiter waiter = NO_WAIT;
  private ProviderCallExecutor calls; // 없으면 build()가 기본 정책·waiter로 만든다
  private AtomicInteger runCounter = new AtomicInteger();
  private CollectionMetrics metrics = TestMetrics.collection();

  private PostgresCollectionRuntime(DataSource dataSource, Clock clock) {
    this.dataSource = dataSource;
    this.clock = clock;
  }

  public static PostgresCollectionRuntime over(DataSource dataSource, Clock clock) {
    return new PostgresCollectionRuntime(dataSource, clock);
  }

  /** 이 런타임이 걷는 커넥터 어댑터(필수). */
  public PostgresCollectionRuntime adapter(SourceAdapter adapter) {
    this.adapter = adapter;
    return this;
  }

  /** 점유 owner. run id는 "{owner}-run-{n}"이다. */
  public PostgresCollectionRuntime owner(String owner) {
    this.owner = owner;
    return this;
  }

  public PostgresCollectionRuntime leaseTtl(Duration ttl) {
    this.leaseTtl = ttl;
    return this;
  }

  public PostgresCollectionRuntime heartbeats(TaskScheduler scheduler) {
    this.heartbeats = scheduler;
    return this;
  }

  /** 공급자 재시도 대기를 취소 토큰으로 실제 기다린다(자식 JVM 프로브용). */
  public PostgresCollectionRuntime waitsForReal() {
    this.waiter = (delay, cancel) -> cancel.awaitCancellation(delay);
    return this;
  }

  /**
   * 공급자 호출 실행기를 직접 준다(정책·대기 기록 — 장애 각본의 짧은 시도 timeout 등). {@link #waitsForReal()}·{@link
   * #metrics}보다 우선한다(MemoryCollectionRuntime.calls와 같다).
   */
  public PostgresCollectionRuntime calls(ProviderCallExecutor calls) {
    this.calls = calls;
    return this;
  }

  /** 여러 런타임(다른 owner)이 run 번호를 이어 쓰게 한다. */
  public PostgresCollectionRuntime runCounter(AtomicInteger counter) {
    this.runCounter = counter;
    return this;
  }

  public PostgresCollectionRuntime metrics(CollectionMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  /** 런타임이 저장된 체크포인트를 읽는 포트를 바꾼다(페이지 커밋은 제품 PostgreSQL 포트 그대로) — 점유 뒤 실패 시험용. */
  public PostgresCollectionRuntime checkpointReads(CheckpointPort reads) {
    this.checkpointReads = reads;
    return this;
  }

  /** run id를 이 공급자로 매긴다(기본: 소유자 + 순번). */
  public PostgresCollectionRuntime runIds(Supplier<RunId> runIds) {
    this.runIds = runIds;
    return this;
  }

  public CollectionRuntime build() {
    if (adapter == null) throw new IllegalStateException("adapter가 없다");
    var adapters = PostgresCollectionConfiguration.adapters(dataSource);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    String runOwner = owner;
    AtomicInteger counter = runCounter;
    return new CollectionRuntime(
        adapters.sources(),
        SourceAdapters.of(List.of(adapter)),
        RawContentCapture.defaults(),
        calls != null
            ? calls
            : new ProviderCallExecutor(
                ProviderCallPolicy.defaults(() -> 0.0),
                clock,
                waiter,
                ProviderCallMetrics.of(metrics.registry())),
        adapters.claims(),
        checkpointReads == null ? adapters.checkpoints() : checkpointReads,
        adapters.runs(),
        new PageCommit(
            tx,
            adapters.claims(),
            adapters.runs(),
            adapters.rawItems(),
            adapters.checkpoints(),
            adapters.outbox(),
            clock,
            metrics,
            adapters.changes(),
            new Progress(clock)),
        new RunCompletion(
            tx,
            adapters.claims(),
            adapters.runs(),
            adapters.rawItems(),
            adapters.outbox(),
            adapters.changes(),
            new SnapshotShrinkGuard(
                adapters.sources(), adapters.guards(), SnapshotShrinkLimits.DEFAULTS, metrics)),
        new OwnerId(runOwner),
        new LeaseHeartbeats(adapters.claims(), leaseTtl, clock, heartbeats, metrics),
        clock,
        runIds == null ? () -> new RunId(runOwner + "-run-" + counter.incrementAndGet()) : runIds,
        metrics);
  }
}
