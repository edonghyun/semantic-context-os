package io.scos.engine.internal.projection;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresAclStore;
import io.scos.store.postgres.PostgresDocumentOwnership;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 커밋과 행 트리거로 동일 투영의 쓰기 비용을 센다. */
@Tag("db")
final class ProjectionWriteScenario implements AutoCloseable {
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final String SOURCE = "slack-synthetic";
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private final DataSource dataSource = PostgresSupport.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final Commits transactions = new Commits(dataSource);
  private final List<SourceDocument> documents =
      IntStream.range(0, 100)
          .mapToObj(
              i ->
                  new SourceDocument(
                      SOURCE,
                      "doc-" + i,
                      "slack://synthetic/" + i,
                      "제목",
                      IngestSamples.BODY_A,
                      "2026-10-01T00:00:00Z",
                      "2026-10-14T00:00:00Z"))
          .toList();
  private final DocumentIngestor ingestor;

  ProjectionWriteScenario() {
    PostgresSupport.truncateAll();
    Embedder embedder = new RecordingEmbedder();
    ingestor =
        new DocumentIngestor(
            new TransactionTemplate(transactions),
            PostgresTestAdapters.unitMaintenance(dataSource),
            PostgresTestAdapters.keywordIndex(dataSource),
            PostgresTestAdapters.vectorIndex(dataSource),
            PostgresTestAdapters.spaceMeta(dataSource),
            embedder,
            EmbeddingCache.NONE,
            new IngestPipeline(new JtokkitTokenizer()),
            new MutableClock(NOW),
            new PostgresAclStore(jdbc),
            new PostgresDocumentOwnership(jdbc));
    jdbc.sql("CREATE TABLE projection_write_counts (kind text)").update();
    jdbc.sql(
            """
        CREATE FUNCTION count_projection_writes() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN INSERT INTO projection_write_counts VALUES (TG_TABLE_NAME); RETURN NULL; END $$
        """)
        .update();
    jdbc.sql(
            "CREATE TRIGGER count_projection_writes AFTER INSERT OR UPDATE ON document_spaces FOR EACH ROW EXECUTE FUNCTION count_projection_writes()")
        .update();
    jdbc.sql(
            "CREATE TRIGGER count_projection_writes AFTER INSERT OR UPDATE ON document_acl FOR EACH ROW EXECUTE FUNCTION count_projection_writes()")
        .update();
  }

  void givenIdenticalProjection(boolean access) {
    project(access);
    transactions.commits = 0;
    jdbc.sql("TRUNCATE projection_write_counts").update();
  }

  void project(boolean access) {
    Map<String, DocumentAcl> acls =
        access
            ? documents.stream()
                .collect(
                    Collectors.toMap(
                        SourceDocument::documentId,
                        doc ->
                            new DocumentAcl(
                                doc.documentId(),
                                DocumentAcl.Status.OK,
                                Set.of(AccessKey.parse("group:slack-synthetic:C1")),
                                NOW)))
            : Map.of();
    ingestor.sync(
        SPACE,
        SOURCE,
        documents,
        documents.stream().map(SourceDocument::documentId).collect(Collectors.toSet()),
        acls,
        () -> {});
  }

  int commits() {
    return transactions.commits;
  }

  int writtenRows() {
    return jdbc.sql("SELECT count(*) FROM projection_write_counts").query(Integer.class).single();
  }

  @Override
  public void close() {
    jdbc.sql("DROP TRIGGER count_projection_writes ON document_spaces").update();
    jdbc.sql("DROP TRIGGER count_projection_writes ON document_acl").update();
    jdbc.sql("DROP FUNCTION count_projection_writes()").update();
    jdbc.sql("DROP TABLE projection_write_counts").update();
  }

  private static final class Commits extends DataSourceTransactionManager {
    private int commits;

    Commits(DataSource source) {
      super(source);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      super.doCommit(status);
      commits++;
    }
  }
}
