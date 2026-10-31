package io.scos.collection.internal.postgres;

import io.scos.spi.connector.RawContent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 데이터를 실은 blob을 호출자 트랜잭션에서 raw_blobs에 쓴다(같은 sha는 한 번만). 참조하는 행보다 먼저 쓴다 — FK가 참조를 강제한다. */
final class BlobWriter {
  private final JdbcClient jdbc;

  BlobWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void store(Optional<RawContent> content, Instant at) {
    if (!(content.orElse(null) instanceof RawContent.Blob b) || b.data().isEmpty()) return;
    jdbc.sql(
            "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, :media, :size, :data, :now) ON CONFLICT (sha256) DO NOTHING")
        .param("sha", b.sha256())
        .param("media", b.mediaType())
        .param("size", b.size())
        .param("data", b.data().get())
        .param("now", Timestamp.from(at))
        .update();
  }
}
