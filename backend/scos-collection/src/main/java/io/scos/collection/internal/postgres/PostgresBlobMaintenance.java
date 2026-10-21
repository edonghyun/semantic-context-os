package io.scos.collection.internal.postgres;

import io.scos.collection.internal.BlobMaintenancePort;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F9 어댑터: 참조 집합은 FK 두 개(raw_items.content_sha256·raw_rejections.quarantined_sha256)의 반대편. 삭제는 SQL 한
 * 문장.
 */
public final class PostgresBlobMaintenance implements BlobMaintenancePort {
  private static final String UNREFERENCED =
      " NOT EXISTS (SELECT 1 FROM raw_items i WHERE i.content_sha256 = b.sha256)"
          + " AND NOT EXISTS (SELECT 1 FROM raw_rejections r WHERE r.quarantined_sha256 = b.sha256)";
  private final JdbcClient jdbc;

  public PostgresBlobMaintenance(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public BlobStats stats(Instant cutoff) {
    return jdbc.sql(
            """
            WITH b AS (
              SELECT b.size, b.stored_at, NOT (%s) AS referenced FROM raw_blobs b)
            SELECT count(*) AS scanned,
                   count(*) FILTER (WHERE referenced) AS by_reference,
                   count(*) FILTER (WHERE NOT referenced AND stored_at >= :cutoff) AS by_grace,
                   count(*) FILTER (WHERE NOT referenced AND stored_at < :cutoff) AS deletable,
                   coalesce(sum(size) FILTER (WHERE NOT referenced AND stored_at < :cutoff), 0) AS bytes
            FROM b
            """
                .formatted(UNREFERENCED))
        .param("cutoff", Timestamp.from(cutoff))
        .query(
            (rs, i) ->
                new BlobStats(
                    rs.getLong("scanned"),
                    rs.getLong("by_reference"),
                    rs.getLong("by_grace"),
                    rs.getLong("deletable"),
                    rs.getLong("bytes")))
        .single();
  }

  @Override
  public int deleteUnreferencedBefore(Instant cutoff) {
    return jdbc.sql("DELETE FROM raw_blobs b WHERE b.stored_at < :cutoff AND" + UNREFERENCED)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
