package io.scos.collection.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.collection.internal.projection.PostgresProjection;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.EmbeddingCache;
import io.scos.support.ManualTaskScheduler;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 DB의 행 쓰기를 감사한다. 관측이 마감 때 삭제돼도 중간 INSERT/UPDATE를 놓치지 않는다. */
@Tag("db")
final class AccessDisabledPostgresScenario implements AutoCloseable {
  private static final List<String> TABLES =
      List.of(
          "raw_acl", "access_observations", "source_groups", "source_identities", "document_acl");
  private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final MutableClock clock = new MutableClock(T0);
  private final FakeSlackServer server;

  AccessDisabledPostgresScenario() throws IOException {
    removeAudit();
    PostgresSupport.truncateAll();
    boolean initialized = false;
    try {
      installAudit();
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource())
          .sources()
          .save(SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, T0));
      server =
          new FakeSlackServer(
              Path.of(
                  "scos-collection/src/test/resources/io/scos/collection/internal/slack-access-dataset.json"));
      initialized = true;
    } finally {
      if (!initialized) removeAudit();
    }
  }

  static void interruptAuditSetup() {
    PostgresSupport.truncateAll();
    installAudit();
  }

  private static void installAudit() {
    var jdbc = JdbcClient.create(PostgresSupport.dataSource());
    jdbc.sql("CREATE TABLE disabled_access_audit (table_name TEXT NOT NULL)").update();
    jdbc.sql(
            """
        CREATE FUNCTION audit_disabled_access() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          INSERT INTO disabled_access_audit VALUES (TG_TABLE_NAME);
          RETURN NEW;
        END $$
        """)
        .update();
    for (String table : TABLES)
      jdbc.sql(
              "CREATE TRIGGER audit_disabled_access AFTER INSERT OR UPDATE ON "
                  + table
                  + " FOR EACH ROW EXECUTE FUNCTION audit_disabled_access()")
          .update();
  }

  record CollectedProjection(ProjectionRun.Outcome outcome, int units, List<String> writes) {}

  CollectedProjection collectAndProject() {
    collect();
    var report = project();
    return new CollectedProjection(report.outcome(), unitCount(), writes());
  }

  private void collect() {
    PostgresCollectionRuntime.over(PostgresSupport.dataSource(), clock)
        .adapter(SlackAssembly.adapter(server.baseUrl(), server.token(), clock))
        .collectAccess(false)
        .build()
        .collect(SlackAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    var run =
        PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource())
            .runs()
            .latest(SlackAssembly.SOURCE)
            .orElseThrow();
    if (!run.status().isSuccess() || run.upserted() != 1)
      throw new IllegalStateException("given 준비 실패: 실제 DB 본문 수집 성공이 필요하다 — " + run);
  }

  private ProjectionRun.Report project() {
    return PostgresProjection.withoutAccessCollection(
            PostgresSupport.dataSource(), clock, FixedEmbedder.zero(4), EmbeddingCache.NONE)
        .run("disabled-access", new ManualTaskScheduler())
        .runDue(CancellationToken.none())
        .getFirst();
  }

  List<String> writes() {
    return jdbc.sql("SELECT table_name FROM disabled_access_audit ORDER BY table_name")
        .query(String.class)
        .list();
  }

  private int unitCount() {
    return jdbc.sql("SELECT count(*)::int FROM units").query(Integer.class).single();
  }

  void writeThenDeleteAcl() {
    jdbc.sql(
            """
        INSERT INTO document_acl (space_id, document_id, status, keys, fingerprint, observed_at)
        VALUES ('legacy', 'audit-probe', 'failed', '{}', 'probe', :at)
        """)
        .param("at", T0.atOffset(ZoneOffset.UTC))
        .update();
    jdbc.sql("DELETE FROM document_acl WHERE document_id='audit-probe'").update();
  }

  private void removeAudit() {
    for (String table : TABLES)
      jdbc.sql("DROP TRIGGER IF EXISTS audit_disabled_access ON " + table).update();
    jdbc.sql("DROP FUNCTION IF EXISTS audit_disabled_access()").update();
    jdbc.sql("DROP TABLE IF EXISTS disabled_access_audit").update();
  }

  @Override
  public void close() {
    removeAudit();
    server.close();
  }
}
