package io.scos.store.postgres;

import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresSpaceMeta implements SpaceMeta {
  private final JdbcClient jdbc;

  public PostgresSpaceMeta(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> get(SpaceId space, String key) {
    return jdbc.sql("SELECT value FROM space_meta WHERE space_id = :space AND key = :key")
        .param("space", space.value())
        .param("key", key)
        .query(String.class)
        .optional();
  }

  @Override
  public void set(SpaceId space, String key, String value) {
    jdbc.sql(
            "INSERT INTO space_meta (space_id, key, value) VALUES (:space, :key, :value) ON CONFLICT (space_id, key) DO UPDATE SET value = EXCLUDED.value")
        .param("space", space.value())
        .param("key", key)
        .param("value", value)
        .update();
  }
}
