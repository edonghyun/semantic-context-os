package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CheckpointConflictException;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * revision 낙관 검사. 첫 전진(expected 0)은 INSERT … ON CONFLICT DO NOTHING, 이후는 조건부 UPDATE … RETURNING.
 */
public final class PostgresCheckpointAdapter implements CheckpointPort {
  private final JdbcClient jdbc;

  public PostgresCheckpointAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public StoredCheckpoint read(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT revision, schema_version, value::text AS value FROM source_checkpoints WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(
            (rs, i) ->
                new StoredCheckpoint(
                    rs.getLong("revision"),
                    Optional.of(
                        new Checkpoint(rs.getInt("schema_version"), rs.getString("value")))))
        .optional()
        .orElse(StoredCheckpoint.none());
  }

  @Override
  public long advance(
      SourceInstanceId source, long expectedRevision, Checkpoint next, Instant now) {
    Optional<Long> revision;
    if (expectedRevision == 0) {
      revision =
          jdbc.sql(
                  """
              INSERT INTO source_checkpoints (source_instance_id, revision, schema_version, value, updated_at)
              VALUES (:source, 1, :schema, CAST(:value AS jsonb), :now)
              ON CONFLICT (source_instance_id) DO NOTHING
              RETURNING revision
              """)
              .param("source", source.value())
              .param("schema", next.schemaVersion())
              .param("value", next.valueJson())
              .param("now", Timestamp.from(now))
              .query(Long.class)
              .optional();
    } else {
      revision =
          jdbc.sql(
                  """
              UPDATE source_checkpoints
              SET revision = revision + 1, schema_version = :schema, value = CAST(:value AS jsonb), updated_at = :now
              WHERE source_instance_id = :source AND revision = :expected
              RETURNING revision
              """)
              .param("schema", next.schemaVersion())
              .param("value", next.valueJson())
              .param("now", Timestamp.from(now))
              .param("source", source.value())
              .param("expected", expectedRevision)
              .query(Long.class)
              .optional();
    }
    return revision.orElseThrow(
        () -> new CheckpointConflictException(expectedRevision, read(source).revision()));
  }
}
