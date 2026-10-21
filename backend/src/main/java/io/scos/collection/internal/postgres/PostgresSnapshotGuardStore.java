package io.scos.collection.internal.postgres;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 스냅샷 축소 보호 상태(V17 snapshot_reconcile_guards·snapshot_shrink_acceptances). 호출자 트랜잭션에 참여한다. */
public final class PostgresSnapshotGuardStore implements SnapshotGuardStore {
  private final JdbcClient jdbc;

  public PostgresSnapshotGuardStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void recordGuarded(SourceInstanceId source, Guarded guarded) {
    jdbc.sql(
            """
            INSERT INTO snapshot_reconcile_guards (source_instance_id, run_id, guarded_at, unobserved_count, active_count, consecutive_count)
            VALUES (:source, :run, :at, :unobserved, :active, :consecutive)
            ON CONFLICT (source_instance_id) DO UPDATE SET run_id = EXCLUDED.run_id, guarded_at = EXCLUDED.guarded_at,
              unobserved_count = EXCLUDED.unobserved_count, active_count = EXCLUDED.active_count,
              consecutive_count = EXCLUDED.consecutive_count
            """)
        .param("source", source.value())
        .param("run", guarded.run().value())
        .param("at", Timestamp.from(guarded.guardedAt()))
        .param("unobserved", guarded.unobservedCount())
        .param("active", guarded.activeCount())
        .param("consecutive", guarded.consecutiveCount())
        .update();
  }

  @Override
  public void clearGuarded(SourceInstanceId source) {
    jdbc.sql("DELETE FROM snapshot_reconcile_guards WHERE source_instance_id = :source")
        .param("source", source.value())
        .update();
  }

  @Override
  public Optional<Guarded> lastGuarded(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT run_id, guarded_at, unobserved_count, active_count, consecutive_count FROM snapshot_reconcile_guards WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(
            (rs, i) ->
                new Guarded(
                    new RunId(rs.getString("run_id")),
                    rs.getTimestamp("guarded_at").toInstant(),
                    rs.getInt("unobserved_count"),
                    rs.getInt("active_count"),
                    rs.getInt("consecutive_count")))
        .optional();
  }

  @Override
  public void acceptShrink(SourceInstanceId source, Instant at) {
    jdbc.sql(
            """
            INSERT INTO snapshot_shrink_acceptances (source_instance_id, accepted_at) VALUES (:source, :at)
            ON CONFLICT (source_instance_id) DO UPDATE SET accepted_at = EXCLUDED.accepted_at, consumed_at = NULL, consumed_run_id = NULL,
              expired_at = NULL
            """)
        .param("source", source.value())
        .param("at", Timestamp.from(at))
        .update();
  }

  @Override
  public Optional<Acceptance> lastAcceptance(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT accepted_at, consumed_at, consumed_run_id, expired_at FROM snapshot_shrink_acceptances WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(
            (rs, i) ->
                new Acceptance(
                    rs.getTimestamp("accepted_at").toInstant(),
                    Optional.ofNullable(rs.getTimestamp("consumed_at")).map(Timestamp::toInstant),
                    Optional.ofNullable(rs.getString("consumed_run_id")).map(RunId::new),
                    Optional.ofNullable(rs.getTimestamp("expired_at")).map(Timestamp::toInstant)))
        .optional();
  }

  @Override
  public boolean consumeAcceptance(SourceInstanceId source, RunId run, Instant at) {
    return jdbc.sql(
                """
                UPDATE snapshot_shrink_acceptances SET consumed_at = :at, consumed_run_id = :run
                WHERE source_instance_id = :source AND consumed_at IS NULL AND expired_at IS NULL
                """)
            .param("at", Timestamp.from(at))
            .param("run", run.value())
            .param("source", source.value())
            .update()
        == 1;
  }

  @Override
  public boolean expireAcceptance(SourceInstanceId source, Instant acceptedAt, Instant at) {
    return jdbc.sql(
                """
                UPDATE snapshot_shrink_acceptances SET expired_at = :at
                WHERE source_instance_id = :source AND accepted_at = :acceptedAt
                  AND consumed_at IS NULL AND expired_at IS NULL
                """)
            .param("at", Timestamp.from(at))
            .param("source", source.value())
            .param("acceptedAt", Timestamp.from(acceptedAt))
            .update()
        == 1;
  }

  @Override
  public void recordBaseline(SourceInstanceId source, RunId run, int activeCount) {
    jdbc.sql(
            """
            INSERT INTO snapshot_run_baselines (source_instance_id, run_id, active_count) VALUES (:source, :run, :active)
            ON CONFLICT (source_instance_id) DO UPDATE SET run_id = EXCLUDED.run_id, active_count = EXCLUDED.active_count
            """)
        .param("source", source.value())
        .param("run", run.value())
        .param("active", activeCount)
        .update();
  }

  @Override
  public Optional<Integer> baseline(SourceInstanceId source, RunId run) {
    return jdbc.sql(
            "SELECT active_count FROM snapshot_run_baselines WHERE source_instance_id = :source AND run_id = :run")
        .param("source", source.value())
        .param("run", run.value())
        .query(Integer.class)
        .optional();
  }
}
