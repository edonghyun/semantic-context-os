package io.scos.collection.internal.postgres;

import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceSchedule;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresSourceInstanceStore implements SourceInstanceStore {
  static final String COLUMNS =
      "id, kind, display_name, config_schema_version, config, credential_ref, enabled, collect_interval_seconds, snapshot_interval_seconds, shrink_guard_min_active, shrink_guard_max_delete_percent, created_at, updated_at";
  private final JdbcClient jdbc;

  public PostgresSourceInstanceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(SourceInstance s) {
    jdbc.sql(
            """
            INSERT INTO source_instances (id, kind, display_name, config_schema_version, config, credential_ref, enabled,
              collect_interval_seconds, snapshot_interval_seconds, shrink_guard_min_active, shrink_guard_max_delete_percent, created_at, updated_at)
            VALUES (:id, :kind, :name, :schema, :config, :cred, :enabled, :collectSeconds, :snapshotSeconds, :minActive, :maxDeletePercent, :created, :updated)
            ON CONFLICT (id) DO UPDATE SET kind = EXCLUDED.kind, display_name = EXCLUDED.display_name, config_schema_version = EXCLUDED.config_schema_version,
              config = EXCLUDED.config, credential_ref = EXCLUDED.credential_ref, enabled = EXCLUDED.enabled,
              collect_interval_seconds = EXCLUDED.collect_interval_seconds, snapshot_interval_seconds = EXCLUDED.snapshot_interval_seconds,
              shrink_guard_min_active = EXCLUDED.shrink_guard_min_active, shrink_guard_max_delete_percent = EXCLUDED.shrink_guard_max_delete_percent,
              updated_at = EXCLUDED.updated_at
            """)
        .param("id", s.id().value())
        .param("kind", s.kind())
        .param("name", s.displayName())
        .param("schema", s.config().schemaVersion())
        .param("config", s.config().valueJson())
        .param("cred", s.credentialRef())
        .param("enabled", s.enabled())
        .param("collectSeconds", seconds(s.schedule().collectInterval()), Types.INTEGER)
        .param("snapshotSeconds", seconds(s.schedule().snapshotInterval()), Types.INTEGER)
        .param("minActive", s.shrinkGuard().minActive().orElse(null), Types.INTEGER)
        .param("maxDeletePercent", s.shrinkGuard().maxDeletePercent().orElse(null), Types.INTEGER)
        .param("created", Timestamp.from(s.createdAt()))
        .param("updated", Timestamp.from(s.updatedAt()))
        .update();
  }

  @Override
  public Optional<SourceInstance> get(SourceInstanceId id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM source_instances WHERE id = :id")
        .param("id", id.value())
        .query(PostgresSourceInstanceStore::map)
        .optional();
  }

  @Override
  public List<SourceInstance> listEnabled() {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM source_instances WHERE enabled ORDER BY id COLLATE \"C\"")
        .query(PostgresSourceInstanceStore::map)
        .list();
  }

  static SourceInstance map(ResultSet rs, int i) throws SQLException {
    return new SourceInstance(
        new SourceInstanceId(rs.getString("id")),
        rs.getString("kind"),
        rs.getString("display_name"),
        new SourceConfig(rs.getInt("config_schema_version"), rs.getString("config")),
        rs.getString("credential_ref"),
        rs.getBoolean("enabled"),
        new SourceSchedule(
            interval(rs, "collect_interval_seconds"), interval(rs, "snapshot_interval_seconds")),
        new SnapshotShrinkOverrides(
            integer(rs, "shrink_guard_min_active"), integer(rs, "shrink_guard_max_delete_percent")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  /** 열 값(초). 없으면 SQL NULL — SourceSchedule이 INTEGER 범위를 보장한다. */
  private static Integer seconds(Optional<Duration> interval) {
    return interval.map(d -> Math.toIntExact(d.toSeconds())).orElse(null);
  }

  private static Optional<Duration> interval(ResultSet rs, String column) throws SQLException {
    int seconds = rs.getInt(column);
    return rs.wasNull() ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
  }

  private static Optional<Integer> integer(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? Optional.empty() : Optional.of(value);
  }
}
