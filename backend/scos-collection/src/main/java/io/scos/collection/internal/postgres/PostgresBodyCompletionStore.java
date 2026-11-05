package io.scos.collection.internal.postgres;

import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.ReconciliationDiscovery.Classification;
import io.scos.collection.internal.ReconciliationDiscovery.Comparison;
import io.scos.collection.internal.ReconciliationDiscovery.Discovery;
import io.scos.collection.internal.RunId;
import io.scos.spi.connector.ReconciliationContext.Path;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 완료 버전·검증 소속을 같은 수집 트랜잭션에서 저장한다. */
public final class PostgresBodyCompletionStore implements BodyCompletionStore {
  private final JdbcClient jdbc;

  public PostgresBodyCompletionStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void savePending(SourceInstanceId source, Pending pending) {
    jdbc.sql(
            """
        INSERT INTO body_enumerations (source_instance_id, fingerprint, page_id, version,
          order_scheme, order_position, run_id, observed)
        VALUES (:source, :fingerprint, :page, :version, :scheme, :position, :run, :observed)
        ON CONFLICT (source_instance_id, fingerprint, page_id) DO UPDATE SET
          version = EXCLUDED.version, order_scheme = EXCLUDED.order_scheme,
          order_position = EXCLUDED.order_position, run_id = EXCLUDED.run_id,
          observed = EXCLUDED.observed
        """)
        .param("source", source.value())
        .param("fingerprint", pending.fingerprint())
        .param("page", pending.pageId())
        .param("version", pending.version())
        .param("scheme", pending.order().scheme())
        .param("position", pending.order().position())
        .param("run", pending.run().value())
        .param("observed", pending.observed().toArray(String[]::new))
        .update();
    recordMembers(source, pending.fingerprint(), pending.pageId(), pending.observed());
  }

  @Override
  public Optional<Pending> pending(SourceInstanceId source, String fingerprint, String pageId) {
    return jdbc.sql(
            """
        SELECT version, order_scheme, order_position, run_id, observed FROM body_enumerations
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND page_id = :page
        """)
        .param("source", source.value())
        .param("fingerprint", fingerprint)
        .param("page", pageId)
        .query(
            (rs, i) ->
                new Pending(
                    fingerprint,
                    pageId,
                    rs.getString("version"),
                    new SourceOrder(rs.getString("order_scheme"), rs.getString("order_position")),
                    new RunId(rs.getString("run_id")),
                    Set.copyOf(Arrays.asList((String[]) rs.getArray("observed").getArray()))))
        .optional();
  }

  @Override
  public boolean hasPending(SourceInstanceId source, String fingerprint, RunId run) {
    return jdbc.sql(
            """
        SELECT EXISTS (SELECT 1 FROM body_enumerations
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND run_id = :run)
        """)
        .param("source", source.value())
        .param("fingerprint", fingerprint)
        .param("run", run.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  public void save(SourceInstanceId source, Body body) {
    jdbc.sql(
            """
        INSERT INTO body_completions (source_instance_id, fingerprint, page_id, version,
          order_scheme, order_position, completed_at, blocks, completed_run_id, last_read_path, last_read_at)
        VALUES (:source, :fingerprint, :page, :version, :scheme, :position, :at, :blocks, :completedRun, :path, :readAt)
        ON CONFLICT (source_instance_id, fingerprint, page_id) DO UPDATE SET
          version = EXCLUDED.version, order_scheme = EXCLUDED.order_scheme,
          order_position = EXCLUDED.order_position, completed_at = EXCLUDED.completed_at,
          blocks = EXCLUDED.blocks, completed_run_id = EXCLUDED.completed_run_id,
          last_read_path = EXCLUDED.last_read_path, last_read_at = EXCLUDED.last_read_at
        """)
        .param("source", source.value())
        .param("fingerprint", body.fingerprint())
        .param("page", body.pageId())
        .param("version", body.version())
        .param("scheme", body.order().scheme())
        .param("position", body.order().position())
        .param("at", Timestamp.from(body.completedAt()))
        .param("blocks", body.blocks().toArray(String[]::new))
        .param("completedRun", body.completedRun().map(RunId::value).orElse(null), Types.VARCHAR)
        .param(
            "path", body.lastReading().map(read -> read.path().name()).orElse(null), Types.VARCHAR)
        .param(
            "readAt",
            body.lastReading().map(read -> read.at().toString()).orElse(null),
            Types.VARCHAR)
        .update();
    jdbc.sql(
            """
        DELETE FROM body_enumerations
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND page_id = :page
        """)
        .param("source", source.value())
        .param("fingerprint", body.fingerprint())
        .param("page", body.pageId())
        .update();
    jdbc.sql(
            "DELETE FROM body_memberships WHERE source_instance_id = :source AND fingerprint = :fingerprint AND page_id = :page")
        .param("source", source.value())
        .param("fingerprint", body.fingerprint())
        .param("page", body.pageId())
        .update();
    recordMembers(source, body.fingerprint(), body.pageId(), body.blocks());
  }

  @Override
  public Optional<Body> find(SourceInstanceId source, String fingerprint, String pageId) {
    return jdbc.sql(
            """
        SELECT version, order_scheme, order_position, completed_at, blocks, completed_run_id, last_read_path, last_read_at FROM body_completions
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND page_id = :page
        """)
        .param("source", source.value())
        .param("fingerprint", fingerprint)
        .param("page", pageId)
        .query(
            (rs, i) ->
                new Body(
                    fingerprint,
                    pageId,
                    rs.getString("version"),
                    new SourceOrder(rs.getString("order_scheme"), rs.getString("order_position")),
                    rs.getTimestamp("completed_at").toInstant(),
                    Set.copyOf(Arrays.asList((String[]) rs.getArray("blocks").getArray())),
                    Optional.ofNullable(rs.getString("completed_run_id")).map(RunId::new),
                    reading(rs.getString("last_read_path"), rs.getString("last_read_at"))))
        .optional();
  }

  private Optional<Reading> reading(String path, String at) {
    return Optional.ofNullable(path)
        .map(value -> new Reading(Path.valueOf(value), Instant.parse(at)));
  }

  @Override
  public Set<String> owners(SourceInstanceId source, String fingerprint, String blockId) {
    return Set.copyOf(
        jdbc.sql(
                """
        SELECT page_id FROM body_memberships
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND block_id = :block
        """)
            .param("source", source.value())
            .param("fingerprint", fingerprint)
            .param("block", blockId)
            .query(String.class)
            .list());
  }

  @Override
  public Set<String> members(SourceInstanceId source, String fingerprint, String pageId) {
    return Set.copyOf(
        jdbc.sql(
                """
        SELECT block_id FROM body_memberships
        WHERE source_instance_id = :source AND fingerprint = :fingerprint AND page_id = :page
        """)
            .param("source", source.value())
            .param("fingerprint", fingerprint)
            .param("page", pageId)
            .query(String.class)
            .list());
  }

  private void recordMembers(
      SourceInstanceId source, String fingerprint, String pageId, Set<String> ids) {
    if (ids.isEmpty()) return;
    jdbc.sql(
            """
        INSERT INTO body_memberships (source_instance_id, fingerprint, page_id, block_id)
        SELECT :source, :fingerprint, :page, unnest(CAST(:blocks AS text[]))
        ON CONFLICT DO NOTHING
        """)
        .param("source", source.value())
        .param("fingerprint", fingerprint)
        .param("page", pageId)
        .param("blocks", ids.toArray(String[]::new))
        .update();
  }

  @Override
  public void recordDiscovery(SourceInstanceId source, RunId run, Discovery discovery) {
    jdbc.sql(
            """
        INSERT INTO reconciliation_discoveries (source_instance_id, run_id, fingerprint,
          resource_id, state_key, kind, classification, eligible)
        VALUES (:source, :run, :fingerprint, :resource, :state, :kind, :classification, :eligible)
        ON CONFLICT (source_instance_id, run_id, resource_id, state_key) DO NOTHING
        """)
        .param("source", source.value())
        .param("run", run.value())
        .param("fingerprint", discovery.fingerprint())
        .param("resource", discovery.resourceId())
        .param("state", discovery.stateKey())
        .param("kind", discovery.kind().wire())
        .param(
            "classification",
            discovery.classification().map(Classification::name).orElse(null),
            Types.VARCHAR)
        .param("eligible", discovery.eligible())
        .update();
    jdbc.sql(
            """
        DELETE FROM reconciliation_comparisons
        WHERE source_instance_id = :source AND run_id = :run AND fingerprint = :fingerprint AND page_id = :page
        """)
        .param("source", source.value())
        .param("run", run.value())
        .param("fingerprint", discovery.fingerprint())
        .param("page", discovery.resourceId())
        .update();
  }

  @Override
  public List<Discovery> discoveries(SourceInstanceId source, RunId run) {
    return jdbc.sql(
            """
        SELECT fingerprint, resource_id, state_key, kind, classification, eligible
        FROM reconciliation_discoveries WHERE source_instance_id = :source AND run_id = :run
        """)
        .param("source", source.value())
        .param("run", run.value())
        .query(
            (rs, index) ->
                new Discovery(
                    rs.getString("fingerprint"),
                    rs.getString("resource_id"),
                    rs.getString("state_key"),
                    ReconciliationKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                    Optional.ofNullable(rs.getString("classification"))
                        .map(Classification::valueOf),
                    rs.getBoolean("eligible")))
        .list();
  }

  @Override
  public void beginComparison(SourceInstanceId source, RunId run, Comparison comparison) {
    jdbc.sql(
            """
        INSERT INTO reconciliation_comparisons (source_instance_id, run_id, fingerprint,
          page_id, previous_state, previous_version, successful_exploration, bootstrap)
        VALUES (:source, :run, :fingerprint, :page, :state, :version, :exploration, :bootstrap)
        ON CONFLICT (source_instance_id, run_id, fingerprint, page_id) DO NOTHING
        """)
        .param("source", source.value())
        .param("run", run.value())
        .param("fingerprint", comparison.fingerprint())
        .param("page", comparison.pageId())
        .param("state", comparison.previousState().orElse(null), Types.VARCHAR)
        .param("version", comparison.previousVersion().orElse(null), Types.VARCHAR)
        .param(
            "exploration",
            comparison.successfulExploration().map(Instant::toString).orElse(null),
            Types.VARCHAR)
        .param("bootstrap", comparison.bootstrap())
        .update();
  }

  @Override
  public Optional<Comparison> comparison(
      SourceInstanceId source, RunId run, String fingerprint, String pageId) {
    return jdbc.sql(
            """
        SELECT previous_state, previous_version, successful_exploration, bootstrap
        FROM reconciliation_comparisons
        WHERE source_instance_id = :source AND run_id = :run AND fingerprint = :fingerprint AND page_id = :page
        """)
        .param("source", source.value())
        .param("run", run.value())
        .param("fingerprint", fingerprint)
        .param("page", pageId)
        .query(
            (rs, index) ->
                new Comparison(
                    fingerprint,
                    pageId,
                    Optional.ofNullable(rs.getString("previous_state")),
                    Optional.ofNullable(rs.getString("previous_version")),
                    Optional.ofNullable(rs.getString("successful_exploration")).map(Instant::parse),
                    rs.getBoolean("bootstrap")))
        .optional();
  }
}
