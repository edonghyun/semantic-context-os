package io.scos.store.sqlite;

import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQLite 공간 메타. 쓰기 트랜잭션이 DB 단위 잠금이라 getLocked는 get과 같다(기본 메서드 그대로). */
public final class SqliteSpaceMeta implements SpaceMeta {
  private final JdbcClient jdbc;

  public SqliteSpaceMeta(JdbcClient jdbc) {
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
            "INSERT INTO space_meta (space_id, key, value) VALUES (:space, :key, :value) ON CONFLICT (space_id, key) DO UPDATE SET value = excluded.value")
        .param("space", space.value())
        .param("key", key)
        .param("value", value)
        .update();
  }

  @Override
  public String putIfAbsent(SpaceId space, String key, String value) {
    jdbc.sql(
            "INSERT INTO space_meta (space_id, key, value) VALUES (:space, :key, :value) ON CONFLICT (space_id, key) DO NOTHING")
        .param("space", space.value())
        .param("key", key)
        .param("value", value)
        .update();
    return get(space, key)
        .orElseThrow(() -> new IllegalStateException("space_meta row vanished: " + key));
  }
}
