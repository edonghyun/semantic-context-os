package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.SourceAdapters;
import io.scos.connector.googledrive.GoogleDriveProjector;
import io.scos.connector.jira.JiraProjector;
import io.scos.connector.notion.NotionProjector;
import io.scos.connector.slack.SlackProjector;
import io.scos.connector.tiro.TiroProjector;
import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.SyncReport;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.Projector;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL 어댑터 위의 실제 투영 조립(DB 종단 테스트·자식 JVM 공용). 투영기는 제품 조립처럼 Slack·Tiro·Jira·Notion이다. 메모리 포트 판은
 * {@link MemoryProjection}. 호출자는 임베더와 바꿀 정책만 넘기고, 점유 owner와 heartbeat 스케줄러는 실행마다 고른다.
 */
public final class PostgresProjection {
  static final SpaceId SPACE = new SpaceId("legacy");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Clock clock;
  private final PostgresCollectionConfiguration.Adapters adapters;
  private final DocumentIngestor ingestor;
  private final DocumentOwnership ownership;
  private final TransactionTemplate tx;
  private boolean collectAccess;
  private Optional<SyncReport> lastSync = Optional.empty();
  private Runnable afterSync = () -> {};

  void afterNextSync(Runnable action) {
    afterSync = action;
  }

  Set<String> ownershipSkipped() {
    return lastSync.orElseThrow().ownershipSkipped();
  }

  private final SourceAdapters sourceAdapters;
  private final List<Projector> projectors =
      new ArrayList<>(
          List.of(
              new SlackProjector(JSON),
              new TiroProjector(JSON),
              new JiraProjector(JSON),
              new NotionProjector(JSON),
              new GoogleDriveProjector(JSON)));

  PostgresProjection projector(Projector projector) {
    projectors.removeIf(existing -> existing.kind().equals(projector.kind()));
    projectors.add(projector);
    return this;
  }

  PostgresProjection withoutAccessCollection() {
    collectAccess = false;
    return this;
  }

  private ProjectionRetryPolicy retry = ProjectionRetryPolicy.defaults();
  private Duration leaseTtl = Duration.ofSeconds(30);
  private ProjectionMetrics metrics = TestMetrics.projection();
  private Map<String, SpaceId> explicitSpaces = Map.of();

  private PostgresProjection(
      DataSource ds,
      Clock clock,
      Embedder embedder,
      EmbeddingCache cache,
      SourceAdapters sourceAdapters,
      boolean collectAccess) {
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    this.sourceAdapters = sourceAdapters;
    this.collectAccess = collectAccess;
    this.clock = clock;
    this.adapters = PostgresCollectionConfiguration.adapters(ds);
    var index = new PostgresIndexConfiguration();
    this.ownership = index.newDocumentOwnership(ds);
    this.ingestor =
        new DocumentIngestor(
            tx,
            PostgresTestAdapters.unitMaintenance(ds),
            PostgresTestAdapters.keywordIndex(ds),
            PostgresTestAdapters.vectorIndex(ds),
            PostgresTestAdapters.spaceMeta(ds),
            embedder,
            cache,
            new IngestPipeline(new JtokkitTokenizer()),
            clock,
            index.newAclStore(ds),
            ownership);
  }

  static PostgresProjection withoutAccessCollection(DataSource ds, Clock clock, Embedder embedder) {
    return withoutAccessCollection(ds, clock, embedder, EmbeddingCache.NONE);
  }

  static PostgresProjection collectingAccess(
      DataSource ds, Clock clock, Embedder embedder, SourceAdapter adapter) {
    return new PostgresProjection(
        ds, clock, embedder, EmbeddingCache.NONE, SourceAdapters.of(List.of(adapter)), true);
  }

  /** 임베딩 캐시(F9, ADR-0026)를 먼저 보는 투영 — 재수집 리허설(migration 서브프로젝트)이 쓴다. */
  public static PostgresProjection withoutAccessCollection(
      DataSource ds, Clock clock, Embedder embedder, EmbeddingCache cache) {
    return new PostgresProjection(ds, clock, embedder, cache, SourceAdapters.of(List.of()), false);
  }

  PostgresProjection retry(ProjectionRetryPolicy policy) {
    this.retry = policy;
    return this;
  }

  PostgresProjection leaseTtl(Duration ttl) {
    this.leaseTtl = ttl;
    return this;
  }

  public PostgresProjection metrics(ProjectionMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  /** 이 소스를 명시 매핑으로 기본 공간({@link #SPACE})에 투영한다(SCOS_SOURCE_SPACES — Tiro처럼 매핑이 필수인 소스). */
  public PostgresProjection explicitlyMapped(String sourceId) {
    this.explicitSpaces = Map.of(sourceId, SPACE);
    return this;
  }

  /** owner로 점유하고 heartbeats 스케줄러로 점유를 갱신하는 투영 실행. 같은 조립의 실행은 임베더·DB를 공유한다. */
  public ProjectionRun run(String owner, TaskScheduler heartbeats) {
    return new ProjectionRun(
        adapters.outbox(),
        adapters.sources(),
        adapters.rawItems(),
        Projectors.of(projectors),
        (space, source, documents, live, acls, guard) -> {
          var report = ingestor.sync(space, source, documents, live, acls, guard);
          lastSync = Optional.of(report);
          Runnable action = afterSync;
          afterSync = () -> {};
          action.run();
          return report;
        },
        new ProjectionSpaces(explicitSpaces, SPACE, JSON),
        retry,
        adapters.claims(),
        new OwnerId(owner),
        new LeaseHeartbeats(
            adapters.claims(), leaseTtl, clock, heartbeats, TestMetrics.collection()),
        clock,
        metrics,
        new Progress(clock),
        sourceAdapters,
        collectAccess,
        ownership,
        adapters.rawAcls(),
        tx,
        AccessCollection.Options.DEFAULTS);
  }
}
