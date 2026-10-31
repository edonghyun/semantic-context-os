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

  /** FOR SHARE: 이 트랜잭션이 끝날 때까지 다른 연결의 UPDATE(재임베딩의 pending 표시 등)를 막는다. */
  @Override
  public Optional<String> getLocked(SpaceId space, String key) {
    return jdbc.sql("SELECT value FROM space_meta WHERE space_id = :space AND key = :key FOR SHARE")
        .param("space", space.value())
        .param("key", key)
        .query(String.class)
        .optional();
  }

  /**
   * 동시에 처음 쓰는 쪽이 있으면 INSERT가 그 커밋을 기다린 뒤 아무것도 하지 않는다. 다시 읽는 값은 먼저 기록한 쪽의 값이고, 잠가 읽으므로 이 트랜잭션 동안 바뀌지
   * 않는다.
   */
  @Override
  public String putIfAbsent(SpaceId space, String key, String value) {
    jdbc.sql(
            "INSERT INTO space_meta (space_id, key, value) VALUES (:space, :key, :value) ON CONFLICT (space_id, key) DO NOTHING")
        .param("space", space.value())
        .param("key", key)
        .param("value", value)
        .update();
    return getLocked(space, key)
        .orElseThrow(() -> new IllegalStateException("space_meta row vanished: " + key));
  }
}
