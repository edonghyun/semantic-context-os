package io.scos.boot;

import io.scos.collection.internal.BlobMaintenancePort;
import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionStores;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunHistoryFreshness;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.postgres.PostgresBlobMaintenance;
import io.scos.collection.internal.postgres.PostgresBodyCompletionStore;
import io.scos.collection.internal.postgres.PostgresCheckpointAdapter;
import io.scos.collection.internal.postgres.PostgresOutboxAdapter;
import io.scos.collection.internal.postgres.PostgresRawItemStore;
import io.scos.collection.internal.postgres.PostgresRunStore;
import io.scos.collection.internal.postgres.PostgresSnapshotGuardStore;
import io.scos.collection.internal.postgres.PostgresSourceChangeLog;
import io.scos.collection.internal.postgres.PostgresSourceInstanceStore;
import io.scos.collection.internal.postgres.PostgresSourceQueries;
import io.scos.collection.internal.postgres.PostgresWorkClaimAdapter;
import io.scos.spi.access.SourceFreshness;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집 모듈의 PostgreSQL 어댑터 조립. 네 어댑터가 **같은** DataSource/트랜잭션 매니저를 쓰므로 PageCommit이 원자적이다. 어댑터가 다른 저장소로
 * 갈라지면 Outbox 어댑터가 그 차이를 흡수해야 한다(ADR-0006·0007).
 */
@Configuration(proxyBeanMethods = false)
public class PostgresCollectionConfiguration {
  /** 지정 DataSource의 수집 이력으로 원천별 최신성을 조립한다. */
  public SourceFreshness newSourceFreshness(DataSource dataSource) {
    return new RunHistoryFreshness(adapters(dataSource).runs());
  }

  @Bean
  JdbcClient collectionJdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }

  @Bean
  WorkClaimPort workClaimPort(
      JdbcClient collectionJdbcClient, PlatformTransactionManager txManager) {
    return new PostgresWorkClaimAdapter(collectionJdbcClient, new TransactionTemplate(txManager));
  }

  @Bean
  CheckpointPort checkpointPort(JdbcClient collectionJdbcClient) {
    return new PostgresCheckpointAdapter(collectionJdbcClient);
  }

  @Bean
  RawItemStore rawItemStore(JdbcClient collectionJdbcClient) {
    return new PostgresRawItemStore(collectionJdbcClient);
  }

  @Bean
  OutboxPort outboxPort(JdbcClient collectionJdbcClient) {
    return new PostgresOutboxAdapter(collectionJdbcClient);
  }

  @Bean
  RunStore runStore(JdbcClient collectionJdbcClient, PlatformTransactionManager txManager) {
    return new PostgresRunStore(collectionJdbcClient, new TransactionTemplate(txManager));
  }

  @Bean
  SourceInstanceStore sourceInstanceStore(JdbcClient collectionJdbcClient) {
    return new PostgresSourceInstanceStore(collectionJdbcClient);
  }

  /** 페이지 커밋·run 완료가 한 트랜잭션에서 함께 쓰는 포트. */
  @Bean
  CollectionStores collectionStores(
      PlatformTransactionManager txManager,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      CheckpointPort checkpoints,
      OutboxPort outbox,
      SourceChangeLog changes,
      BodyCompletionStore bodies,
      SourceInstanceStore sources) {
    return new CollectionStores(
            new TransactionTemplate(txManager),
            claims,
            runs,
            rawItems,
            checkpoints,
            outbox,
            changes)
        .withBodies(bodies)
        .withSources(sources);
  }

  @Bean
  BodyCompletionStore bodyCompletionStore(JdbcClient collectionJdbcClient) {
    return new PostgresBodyCompletionStore(collectionJdbcClient);
  }

  @Bean
  PageCommit pageCommit(
      CollectionStores stores, Clock clock, CollectionMetrics metrics, Progress progress) {
    return new PageCommit(stores, clock, metrics, progress);
  }

  @Bean
  SnapshotGuardStore snapshotGuardStore(JdbcClient collectionJdbcClient) {
    return new PostgresSnapshotGuardStore(collectionJdbcClient);
  }

  @Bean
  RunCompletion runCompletion(CollectionStores stores, SnapshotShrinkGuard shrinkGuard) {
    return new RunCompletion(stores, shrinkGuard);
  }

  /** F10 변경 로그(append는 PageCommit·RunCompletion 트랜잭션 안, 읽기·정리는 자체 트랜잭션). */
  @Bean
  SourceChangeLog sourceChangeLog(
      JdbcClient collectionJdbcClient, PlatformTransactionManager txManager) {
    return new PostgresSourceChangeLog(collectionJdbcClient, new TransactionTemplate(txManager));
  }

  /** 운영 조회 CLI(읽기 전용). */
  @Bean
  SourceQueries sourceQueries(JdbcClient collectionJdbcClient, SnapshotGuardStore guards) {
    return new PostgresSourceQueries(collectionJdbcClient, guards);
  }

  /** F9 고아 blob 집계·삭제. */
  @Bean
  BlobMaintenancePort blobMaintenancePort(JdbcClient collectionJdbcClient) {
    return new PostgresBlobMaintenance(collectionJdbcClient);
  }

  /** 테스트·실험이 Spring 컨텍스트 없이 같은 조립을 재사용한다. */
  public static Adapters adapters(DataSource dataSource) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    var guards = new PostgresSnapshotGuardStore(jdbc);
    // 빈 조립처럼 트랜잭션 관리자 하나를 셋이 같이 쓴다(관리자는 상태가 없고 트랜잭션은 DataSource에 묶인다)
    var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new Adapters(
        new PostgresWorkClaimAdapter(jdbc, tx),
        new PostgresCheckpointAdapter(jdbc),
        new PostgresRawItemStore(jdbc),
        new PostgresOutboxAdapter(jdbc),
        new PostgresRunStore(jdbc, tx),
        new PostgresSourceInstanceStore(jdbc),
        new PostgresSourceChangeLog(jdbc, tx),
        new PostgresBlobMaintenance(jdbc),
        new PostgresSourceQueries(jdbc, guards),
        guards,
        new PostgresBodyCompletionStore(jdbc));
  }

  public record Adapters(
      WorkClaimPort claims,
      CheckpointPort checkpoints,
      RawItemStore rawItems,
      OutboxPort outbox,
      RunStore runs,
      SourceInstanceStore sources,
      SourceChangeLog changes,
      BlobMaintenancePort blobs,
      SourceQueries queries,
      SnapshotGuardStore guards,
      BodyCompletionStore bodies) {
    /** 이 어댑터들이 tx 안에서 함께 쓰는 묶음. */
    public CollectionStores stores(TransactionOperations tx) {
      return new CollectionStores(tx, claims, runs, rawItems, checkpoints, outbox, changes)
          .withBodies(bodies)
          .withSources(sources);
    }
  }
}
