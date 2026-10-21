package io.scos.collection.internal.postgres;

import io.scos.collection.internal.MutationRules;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F4 의미를 한 행 잠금(SELECT … FOR UPDATE) 뒤 UPSERT로 적용한다. blob 데이터는 같은 문장 흐름(호출자 트랜잭션)에서 raw_blobs에 쓴다.
 */
public final class PostgresRawItemStore implements RawItemStore {
  static final String COLUMNS =
      "external_id, state, resource_type, source_version, order_scheme, order_position, source_created_at, source_updated_at, observed_at, metadata, content_storage, content_media_type, content_inline, content_sha256, content_size, deleted_at";
  private final JdbcClient jdbc;

  public PostgresRawItemStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Outcome apply(SourceInstanceId source, RawMutation mutation) {
    Optional<RawRecord> existing =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM raw_items WHERE source_instance_id = :source AND external_id = :id FOR UPDATE")
            .param("source", source.value())
            .param("id", mutation.externalId())
            .query(PostgresRawItemStore::map)
            .optional();
    return switch (mutation) {
      case RawMutation.Upsert u -> {
        if (MutationRules.isStale(existing, u.item().sourceOrder())) yield Outcome.UNCHANGED;
        if (existing.orElse(null) instanceof RawRecord.Active a
            && a.item().sourceVersion().equals(u.item().sourceVersion())) yield Outcome.UNCHANGED;
        writeActive(source, u.item());
        yield Outcome.UPSERTED;
      }
      case RawMutation.Delete d -> {
        if (MutationRules.isStale(existing, d.sourceOrder())) yield Outcome.UNCHANGED;
        RawRecord.Deleted tomb = MutationRules.tombstone(existing, d);
        writeTombstone(source, tomb);
        yield Outcome.DELETED;
      }
    };
  }

  private void writeActive(SourceInstanceId source, RawItem item) {
    RawContent c = item.content().orElse(null);
    if (c instanceof RawContent.Blob b && b.data().isPresent()) {
      jdbc.sql(
              "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, :media, :size, :data, :now) ON CONFLICT (sha256) DO NOTHING")
          .param("sha", b.sha256())
          .param("media", b.mediaType())
          .param("size", b.size())
          .param("data", b.data().get())
          .param("now", Timestamp.from(item.observedAt()))
          .update();
    }
    BlobReferences.translating(
        () ->
            jdbc.sql(
                    """
            INSERT INTO raw_items (source_instance_id, external_id, state, resource_type, source_version, order_scheme, order_position,
                                   source_created_at, source_updated_at, observed_at, metadata, content_storage, content_media_type,
                                   content_inline, content_sha256, content_size, deleted_at)
            VALUES (:source, :id, 'active', :type, :version, :scheme, :position, :created, :updated, :observed, :metadata,
                    :storage, :media, :inline, :sha, :size, NULL)
            ON CONFLICT (source_instance_id, external_id) DO UPDATE SET
              state = 'active', resource_type = EXCLUDED.resource_type, source_version = EXCLUDED.source_version,
              order_scheme = EXCLUDED.order_scheme, order_position = EXCLUDED.order_position,
              source_created_at = EXCLUDED.source_created_at, source_updated_at = EXCLUDED.source_updated_at,
              observed_at = EXCLUDED.observed_at, metadata = EXCLUDED.metadata, content_storage = EXCLUDED.content_storage,
              content_media_type = EXCLUDED.content_media_type, content_inline = EXCLUDED.content_inline,
              content_sha256 = EXCLUDED.content_sha256, content_size = EXCLUDED.content_size, deleted_at = NULL
            """)
                .param("source", source.value())
                .param("id", item.externalId())
                .param("type", item.resourceType())
                .param("version", item.sourceVersion())
                .param("scheme", item.sourceOrder().map(SourceOrder::scheme).orElse(null))
                .param("position", item.sourceOrder().map(SourceOrder::position).orElse(null))
                .param("created", item.sourceCreatedAt().orElse(null))
                .param("updated", item.sourceUpdatedAt().orElse(null))
                .param("observed", Timestamp.from(item.observedAt()))
                .param("metadata", item.metadataJson())
                .param(
                    "storage",
                    c == null ? null : c instanceof RawContent.Inline ? "inline" : "blob")
                .param("media", c == null ? null : c.mediaType())
                .param("inline", c instanceof RawContent.Inline i ? i.valueJson() : null)
                .param("sha", c instanceof RawContent.Blob b ? b.sha256() : null)
                .param("size", c instanceof RawContent.Blob b ? b.size() : null)
                .update());
  }

  private void writeTombstone(SourceInstanceId source, RawRecord.Deleted t) {
    jdbc.sql(
            """
            INSERT INTO raw_items (source_instance_id, external_id, state, resource_type, source_version, order_scheme, order_position,
                                   observed_at, metadata, deleted_at)
            VALUES (:source, :id, 'deleted', :type, :version, :scheme, :position, :observed, NULL, :deleted)
            ON CONFLICT (source_instance_id, external_id) DO UPDATE SET
              state = 'deleted', resource_type = EXCLUDED.resource_type, source_version = EXCLUDED.source_version,
              order_scheme = EXCLUDED.order_scheme, order_position = EXCLUDED.order_position, source_created_at = NULL,
              source_updated_at = NULL, observed_at = EXCLUDED.observed_at, metadata = NULL, content_storage = NULL,
              content_media_type = NULL, content_inline = NULL, content_sha256 = NULL, content_size = NULL, deleted_at = EXCLUDED.deleted_at
            """)
        .param("source", source.value())
        .param("id", t.externalId())
        .param("type", t.resourceType().orElse(null))
        .param("version", t.lastSourceVersion().orElse(null))
        .param("scheme", t.sourceOrder().map(SourceOrder::scheme).orElse(null))
        .param("position", t.sourceOrder().map(SourceOrder::position).orElse(null))
        .param("observed", Timestamp.from(t.observedAt()))
        .param("deleted", Timestamp.from(t.deletedAt()))
        .update();
  }

  @Override
  public Optional<RawRecord> get(SourceInstanceId source, String externalId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM raw_items WHERE source_instance_id = :source AND external_id = :id")
        .param("source", source.value())
        .param("id", externalId)
        .query(PostgresRawItemStore::map)
        .optional();
  }

  @Override
  public Page listActive(SourceInstanceId source, int limit, Optional<String> cursor) {
    if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
    List<RawRecord> rows =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM raw_items WHERE source_instance_id = :source AND state = 'active'"
                    + " AND (CAST(:cursor AS text) IS NULL OR external_id COLLATE \"C\" > CAST(:cursor AS text)) ORDER BY external_id COLLATE \"C\" LIMIT :limit")
            .param("source", source.value())
            .param("cursor", cursor.orElse(null))
            .param("limit", limit + 1)
            .query(PostgresRawItemStore::map)
            .list();
    List<RawItem> items = new ArrayList<>();
    for (RawRecord r : rows.subList(0, Math.min(limit, rows.size())))
      if (r instanceof RawRecord.Active a) items.add(a.item());
    Optional<String> next =
        rows.size() > limit
            ? Optional.of(items.get(items.size() - 1).externalId())
            : Optional.empty();
    return new Page(items, next);
  }

  @Override
  public int count(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT count(*) FROM raw_items WHERE source_instance_id = :source AND state = 'active'")
        .param("source", source.value())
        .query(Integer.class)
        .single();
  }

  @Override
  public int tombstoneCount(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT count(*) FROM raw_items WHERE source_instance_id = :source AND state = 'deleted'")
        .param("source", source.value())
        .query(Integer.class)
        .single();
  }

  @Override
  public int countUnobserved(SourceInstanceId source, Set<String> observedExternalIds) {
    // 수만 센다 — active id 목록을 Java로 옮기지 않는다(정리할 때만 tombstoneUnobserved가 읽는다)
    return jdbc.sql(
            """
            SELECT count(*) FROM raw_items
            WHERE source_instance_id = :source AND state = 'active' AND NOT (external_id = ANY(:observed))
            """)
        .param("source", source.value())
        .param("observed", observedExternalIds.toArray(String[]::new))
        .query(Integer.class)
        .single();
  }

  @Override
  public List<String> tombstoneUnobserved(
      SourceInstanceId source, Set<String> observedExternalIds, Instant at) {
    List<String> active =
        jdbc.sql(
                "SELECT external_id FROM raw_items WHERE source_instance_id = :source AND state = 'active' FOR UPDATE")
            .param("source", source.value())
            .query(String.class)
            .list();
    String[] absent =
        active.stream().filter(id -> !observedExternalIds.contains(id)).toArray(String[]::new);
    if (absent.length == 0) return List.of();
    return jdbc.sql(
            """
            UPDATE raw_items SET state = 'deleted', order_scheme = NULL, order_position = NULL, source_created_at = NULL,
              source_updated_at = NULL, observed_at = :at, metadata = NULL, content_storage = NULL, content_media_type = NULL,
              content_inline = NULL, content_sha256 = NULL, content_size = NULL, deleted_at = :at
            WHERE source_instance_id = :source AND state = 'active' AND external_id = ANY(:ids)
            RETURNING external_id
            """)
        .param("at", Timestamp.from(at))
        .param("source", source.value())
        .param("ids", absent)
        .query(String.class)
        .list();
  }

  @Override
  public Optional<byte[]> blob(String sha256) {
    return jdbc.sql("SELECT data FROM raw_blobs WHERE sha256 = :sha")
        .param("sha", sha256)
        .query(byte[].class)
        .optional();
  }

  static RawRecord map(ResultSet rs, int i) throws SQLException {
    String id = rs.getString("external_id");
    Optional<SourceOrder> order =
        rs.getString("order_scheme") == null
            ? Optional.empty()
            : Optional.of(
                new SourceOrder(rs.getString("order_scheme"), rs.getString("order_position")));
    if (rs.getString("state").equals("deleted")) {
      return new RawRecord.Deleted(
          id,
          Optional.ofNullable(rs.getString("resource_type")),
          Optional.ofNullable(rs.getString("source_version")),
          order,
          rs.getTimestamp("observed_at").toInstant(),
          rs.getTimestamp("deleted_at").toInstant());
    }
    String storage = rs.getString("content_storage");
    Optional<RawContent> content =
        storage == null
            ? Optional.empty()
            : storage.equals("inline")
                ? Optional.of(
                    new RawContent.Inline(
                        rs.getString("content_media_type"), rs.getString("content_inline")))
                : Optional.of(
                    new RawContent.Blob(
                        rs.getString("content_media_type"),
                        rs.getString("content_sha256"),
                        rs.getLong("content_size"),
                        Optional.empty()));
    return new RawRecord.Active(
        new RawItem(
            id,
            rs.getString("resource_type"),
            rs.getString("source_version"),
            order,
            Optional.ofNullable(rs.getString("source_created_at")),
            Optional.ofNullable(rs.getString("source_updated_at")),
            rs.getTimestamp("observed_at").toInstant(),
            rs.getString("metadata"),
            content));
  }
}
