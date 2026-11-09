package io.scos.collection.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawAclStore;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.spi.access.AclStore;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
import io.scos.support.TestMetrics;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 페이지 커밋과 run 완료의 테스트 조립: 같은 트랜잭션을 나누는 포트 묶음 위에 {@link PageCommit}·{@link RunCompletion}을 기본
 * 협력자(테스트 지표, 기본 축소 기준)로 세운다. 테스트 본문은 포트로 준비하고 두 유스케이스를 부른다.
 */
public final class CommitContext {
  /** 페이지 커밋·완료가 같은 트랜잭션에서 함께 쓰는 포트. */
  public record Ports(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      CheckpointPort checkpoints,
      OutboxPort outbox,
      SourceChangeLog changes,
      RawAclStore rawAcls,
      AccessObservationStore accessObservations,
      SourceInstanceStore sources,
      SnapshotGuardStore guards) {
    /** 같은 DataSource의 Postgres 어댑터와 그 위의 트랜잭션. */
    public static Ports postgres(DataSource ds) {
      PostgresCollectionConfiguration.Adapters a = PostgresCollectionConfiguration.adapters(ds);
      return new Ports(
          new TransactionTemplate(new DataSourceTransactionManager(ds)),
          a.claims(),
          a.runs(),
          a.rawItems(),
          a.checkpoints(),
          a.outbox(),
          a.changes(),
          a.rawAcls(),
          a.accessObservations(),
          a.sources(),
          a.guards());
    }

    /** 변경 로그만 바꾼 묶음(변경 로그 장애 주입). */
    public Ports withChanges(SourceChangeLog changes) {
      return new Ports(
          tx,
          claims,
          runs,
          rawItems,
          checkpoints,
          outbox,
          changes,
          rawAcls,
          accessObservations,
          sources,
          guards);
    }

    /** 관측/원본 읽기 경계의 장애만 주입하고 나머지 저장 자원은 공유한다. */
    Ports withReads(RunStore runReads, RawItemStore rawReads, RawAclStore aclReads) {
      return new Ports(
          tx,
          claims,
          runReads,
          rawReads,
          checkpoints,
          outbox,
          changes,
          aclReads,
          accessObservations,
          sources,
          guards);
    }

    Ports withTransactions(TransactionOperations transactions) {
      return new Ports(
          transactions,
          claims,
          runs,
          rawItems,
          checkpoints,
          outbox,
          changes,
          rawAcls,
          accessObservations,
          sources,
          guards);
    }

    CollectionStores stores() {
      return new CollectionStores(
              tx, claims, runs, rawItems, checkpoints, outbox, changes, rawAcls, accessObservations)
          .withSources(sources);
    }
  }

  /** 메모리 포트(구현 타입 그대로 — 테스트가 메모리 쪽 관찰 메서드를 쓴다). 트랜잭션은 없다. */
  public record Memory(
      MemoryWorkClaimPort claims,
      MemoryRunStore runs,
      MemoryRawItemStore rawItems,
      MemoryCheckpointPort checkpoints,
      MemoryOutboxPort outbox,
      MemorySourceChangeLog changes,
      MemoryRawAclStore rawAcls,
      AccessObservationStore accessObservations,
      MemorySourceInstanceStore sources,
      MemorySnapshotGuardStore guards) {
    public static Memory create() {
      return create(new MemoryRawAclStore());
    }

    static Memory create(MemoryRawAclStore rawAcls) {
      return create(rawAcls, new MemoryAclStore());
    }

    static Memory create(MemoryRawAclStore rawAcls, AclStore documents) {
      return create(rawAcls, documents, new MemoryDocumentOwnership());
    }

    static Memory create(
        MemoryRawAclStore rawAcls, AclStore documents, MemoryDocumentOwnership ownership) {
      var runs = MemoryRunStore.create();
      return new Memory(
          new MemoryWorkClaimPort(),
          runs,
          new MemoryRawItemStore(rawAcls, documents, ownership),
          new MemoryCheckpointPort(),
          new MemoryOutboxPort(),
          new MemorySourceChangeLog(),
          rawAcls,
          runs.accessObservations(),
          new MemorySourceInstanceStore(runs.identities()),
          new MemorySnapshotGuardStore());
    }

    public Ports ports() {
      return new Ports(
          TransactionOperations.withoutTransaction(),
          claims,
          runs,
          rawItems,
          checkpoints,
          outbox,
          changes,
          rawAcls,
          accessObservations,
          sources,
          guards);
    }
  }

  private final Ports ports;
  private final Clock clock;
  private final Progress progress;

  public CommitContext(Ports ports, Clock clock) {
    this.ports = ports;
    this.clock = clock;
    this.progress = new Progress(clock);
  }

  public Progress progress() {
    return progress;
  }

  public PageCommit pageCommit() {
    return new PageCommit(ports.stores(), clock, TestMetrics.collection(), progress);
  }

  public RunCompletion completion() {
    return new RunCompletion(
        ports.stores(),
        new SnapshotShrinkGuard(
            ports.sources(),
            ports.guards(),
            SnapshotShrinkLimits.DEFAULTS,
            TestMetrics.collection()));
  }
}
