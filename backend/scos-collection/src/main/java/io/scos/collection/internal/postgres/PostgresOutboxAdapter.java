package io.scos.collection.internal.postgres;

import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 소스당 1행 Outbox. 호출자 트랜잭션에 참여하므로 커밋과 함께만 보인다(같은 DataSource일 때). 완료·실패는 version 조건부 한 문장. */
public final class PostgresOutboxAdapter implements OutboxPort {
  private static final String COLUMNS =
      "source_instance_id, version, requested_at, attempts, consecutive_failures, next_attempt_at, parked, last_error";
  private final JdbcClient jdbc;

  public PostgresOutboxAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void requestProjection(SourceInstanceId source, Instant now) {
    jdbc.sql(
            """
            INSERT INTO projection_requests (source_instance_id, requested_at, version) VALUES (:source, :now, 1)
            ON CONFLICT (source_instance_id) DO UPDATE
              SET requested_at = EXCLUDED.requested_at, version = projection_requests.version + 1, attempts = 0, next_attempt_at = NULL, parked = FALSE
            """) // consecutive_failures는 그대로 — 완료(DELETE)만 0으로 돌린다
        .param("source", source.value())
        .param("now", Timestamp.from(now))
        .update();
  }

  @Override
  public Summary summary() {
    return jdbc.sql(
            "SELECT count(*) FILTER (WHERE NOT parked) AS pending, count(*) FILTER (WHERE parked) AS parked, coalesce(max(consecutive_failures), 0) AS max_failures FROM projection_requests")
        .query(
            (rs, i) ->
                new Summary(rs.getInt("pending"), rs.getInt("parked"), rs.getInt("max_failures")))
        .single();
  }

  @Override
  public List<SourceInstanceId> pending() {
    return jdbc.sql(
            "SELECT source_instance_id FROM projection_requests ORDER BY requested_at, source_instance_id")
        .query((rs, i) -> new SourceInstanceId(rs.getString(1)))
        .list();
  }

  @Override
  public List<SourceInstanceId> due(Instant now) {
    return jdbc.sql(
            "SELECT source_instance_id FROM projection_requests WHERE NOT parked AND (next_attempt_at IS NULL OR next_attempt_at <= :now) ORDER BY requested_at, source_instance_id")
        .param("now", Timestamp.from(now))
        .query((rs, i) -> new SourceInstanceId(rs.getString(1)))
        .list();
  }

  @Override
  public Optional<Request> request(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM projection_requests WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(
            (rs, i) ->
                new Request(
                    new SourceInstanceId(rs.getString("source_instance_id")),
                    rs.getLong("version"),
                    rs.getTimestamp("requested_at").toInstant(),
                    rs.getInt("attempts"),
                    rs.getInt("consecutive_failures"),
                    Optional.ofNullable(rs.getTimestamp("next_attempt_at"))
                        .map(Timestamp::toInstant),
                    rs.getBoolean("parked"),
                    Optional.ofNullable(rs.getString("last_error"))))
        .optional();
  }

  @Override
  public boolean complete(SourceInstanceId source, long seenVersion) {
    return jdbc.sql(
                "DELETE FROM projection_requests WHERE source_instance_id = :source AND version = :version")
            .param("source", source.value())
            .param("version", seenVersion)
            .update()
        == 1;
  }

  @Override
  public boolean recordFailure(
      SourceInstanceId source,
      long seenVersion,
      Instant now,
      String error,
      ProjectionRetryPolicy policy) {
    Optional<Integer> attempts =
        jdbc.sql(
                "UPDATE projection_requests SET attempts = attempts + 1, consecutive_failures = consecutive_failures + 1, last_error = :error WHERE source_instance_id = :source AND version = :version RETURNING attempts")
            .param("error", error)
            .param("source", source.value())
            .param("version", seenVersion)
            .query(Integer.class)
            .optional();
    if (attempts.isEmpty()) return false;
    jdbc.sql(
            "UPDATE projection_requests SET next_attempt_at = :next, parked = :parked WHERE source_instance_id = :source AND version = :version")
        .param("next", Timestamp.from(now.plus(policy.backoff(attempts.get()))))
        .param("parked", policy.parks(attempts.get()))
        .param("source", source.value())
        .param("version", seenVersion)
        .update();
    return true;
  }
}
