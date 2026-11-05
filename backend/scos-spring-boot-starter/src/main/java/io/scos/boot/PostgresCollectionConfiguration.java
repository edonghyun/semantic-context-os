package io.scos.boot;

import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.AccessObservationStore;
import io.scos.collection.internal.BlobMaintenancePort;
import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionStores;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawAclStore;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunHistoryFreshness;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.postgres.PostgresAccessObservationStore;
import io.scos.collection.internal.postgres.PostgresBlobMaintenance;
import io.scos.collection.internal.postgres.PostgresBodyCompletionStore;
import io.scos.collection.internal.postgres.PostgresCheckpointAdapter;
import io.scos.collection.internal.postgres.PostgresOutboxAdapter;
import io.scos.collection.internal.postgres.PostgresRawAclStore;
import io.scos.collection.internal.postgres.PostgresRawItemStore;
import io.scos.collection.internal.postgres.PostgresRunStore;
import io.scos.collection.internal.postgres.PostgresSnapshotGuardStore;
import io.scos.collection.internal.postgres.PostgresSourceChangeLog;
import io.scos.collection.internal.postgres.PostgresSourceInstanceStore;
import io.scos.collection.internal.postgres.PostgresSourceQueries;
import io.scos.collection.internal.postgres.PostgresWorkClaimAdapter;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.store.SpaceId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
  RawItemStore rawItemStore(JdbcClient collectionJdbcClient, RawAclStore rawAcls) {
    return new PostgresRawItemStore(collectionJdbcClient, rawAcls);
  }

  @Bean
  RawAclStore rawAclStore(JdbcClient collectionJdbcClient, PlatformTransactionManager txManager) {
    return new PostgresRawAclStore(collectionJdbcClient, new TransactionTemplate(txManager));
  }

  @Bean
  AccessObservationStore accessObservationStore(
      JdbcClient collectionJdbcClient,
      PlatformTransactionManager txManager,
      SourceAccessDirectory directory) {
    return new PostgresAccessObservationStore(collectionJdbcClient, txManager, directory);
  }

  @Bean
  OutboxPort outboxPort(JdbcClient collectionJdbcClient) {
    return new PostgresOutboxAdapter(collectionJdbcClient);
  }

  @Bean
  RunStore runStore(
      JdbcClient collectionJdbcClient,
      PlatformTransactionManager txManager,
      AccessObservationStore observations) {
    return new PostgresRunStore(
        collectionJdbcClient, new TransactionTemplate(txManager), observations);
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
      RawAclStore rawAcls,
      AccessObservationStore accessObservations,
      BodyCompletionStore bodies,
      SourceInstanceStore sources) {
    return new CollectionStores(
            new TransactionTemplate(txManager),
            claims,
            runs,
            rawItems,
            checkpoints,
            outbox,
            changes,
            rawAcls,
            accessObservations)
        .withBodies(bodies)
        .withSources(sources);
  }

  @Bean
  BodyCompletionStore bodyCompletionStore(JdbcClient collectionJdbcClient) {
    return new PostgresBodyCompletionStore(collectionJdbcClient);
  }

  @Bean
  PageCommit pageCommit(
      CollectionStores stores,
      Clock clock,
      CollectionMetrics metrics,
      Progress progress,
      SourceAdapters adapters,
      AccessProperties access,
      AccessCollection.Options accessOptions) {
    return new PageCommit(
        stores, clock, metrics, progress, adapters, access.collectAccess(), accessOptions);
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
  SourceQueries sourceQueries(
      JdbcClient collectionJdbcClient,
      SnapshotGuardStore guards,
      RawAclStore rawAcls,
      ProjectionSpaces spaces,
      Clock clock,
      AccessCollection.Options accessOptions,
      SourceAdapters adapters,
      AclStore acls) {
    return new PostgresSourceQueries(
        collectionJdbcClient, guards, rawAcls, spaces, clock, accessOptions, adapters, acls);
  }

  /** F9 고아 blob 집계·삭제. */
  @Bean
  BlobMaintenancePort blobMaintenancePort(JdbcClient collectionJdbcClient) {
    return new PostgresBlobMaintenance(collectionJdbcClient);
  }

  /** 테스트·실험이 Spring 컨텍스트 없이 같은 조립을 재사용한다. */
  public static Adapters adapters(DataSource dataSource) {
    return new PostgresCollectionConfiguration()
        .newAdapters(dataSource, SourceAdapters.of(List.of()));
  }

  public Adapters newAdapters(DataSource dataSource, SourceAdapters adapters) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    var guards = new PostgresSnapshotGuardStore(jdbc);
    // 빈 조립처럼 트랜잭션 관리자 하나를 셋이 같이 쓴다(관리자는 상태가 없고 트랜잭션은 DataSource에 묶인다)
    var manager = new DataSourceTransactionManager(dataSource);
    var tx = new TransactionTemplate(manager);
    var rawAcls = new PostgresRawAclStore(jdbc, tx);
    var observations =
        new PostgresAccessObservationStore(
            jdbc, manager, new PostgresIndexConfiguration().newSourceAccessDirectory(dataSource));
    return new Adapters(
        new PostgresWorkClaimAdapter(jdbc, tx),
        new PostgresCheckpointAdapter(jdbc),
        new PostgresRawItemStore(jdbc, rawAcls),
        new PostgresOutboxAdapter(jdbc),
        new PostgresRunStore(jdbc, tx, observations),
        new PostgresSourceInstanceStore(jdbc),
        new PostgresSourceChangeLog(jdbc, tx),
        rawAcls,
        observations,
        new PostgresBlobMaintenance(jdbc),
        new PostgresSourceQueries(
            jdbc,
            guards,
            rawAcls,
            new ProjectionSpaces(Map.of(), new SpaceId("legacy"), new ObjectMapper()),
            Clock.systemUTC(),
            AccessCollection.Options.DEFAULTS,
            adapters,
            new PostgresIndexConfiguration().newAclStore(dataSource)),
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
      RawAclStore rawAcls,
      AccessObservationStore accessObservations,
      BlobMaintenancePort blobs,
      SourceQueries queries,
      SnapshotGuardStore guards,
      BodyCompletionStore bodies) {
    /** 이 어댑터들이 tx 안에서 함께 쓰는 묶음. */
    public CollectionStores stores(TransactionOperations tx) {
      return new CollectionStores(
              tx, claims, runs, rawItems, checkpoints, outbox, changes, rawAcls, accessObservations)
          .withBodies(bodies)
          .withSources(sources);
    }
  }
}
