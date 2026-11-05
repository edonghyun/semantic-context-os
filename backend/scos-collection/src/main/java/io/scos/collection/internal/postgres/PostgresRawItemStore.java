package io.scos.collection.internal.postgres;

import io.scos.collection.internal.MutationDecision;
import io.scos.collection.internal.RawAclStore;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F4 의미를 한 행 잠금(SELECT … FOR UPDATE) 뒤 UPSERT로 적용한다. blob 데이터는 같은 문장 흐름(호출자 트랜잭션)에서 raw_blobs에 쓴다.
 */
public final class PostgresRawItemStore implements RawItemStore {
  static final String COLUMNS =
      "external_id, state, resource_type, source_version, order_scheme, order_position, source_created_at, source_updated_at, observed_at, metadata, content_storage, content_media_type, content_inline, content_sha256, content_size, deleted_at";
  static final String READ_COLUMNS =
      Arrays.stream(COLUMNS.split(", "))
              .map(column -> "r." + column)
              .collect(Collectors.joining(", "))
          + ", a.status AS acl_status, a.keys AS acl_keys, a.observed_at AS acl_observed_at, a.reason AS acl_reason";
  static final String READ_FROM =
      " FROM raw_items r LEFT JOIN raw_acl a ON r.source_instance_id = a.source_instance_id AND r.external_id = a.external_id";
  private final JdbcClient jdbc;
  private final BlobWriter blobs;
  private final RawAclStore acls;

  public PostgresRawItemStore(JdbcClient jdbc, RawAclStore acls) {
    this.acls = acls;
    this.jdbc = jdbc;
    this.blobs = new BlobWriter(jdbc);
  }

  @Override
  public Page accessCandidates(
      SourceInstanceId source,
      Set<String> kinds,
      Optional<Instant> cutoff,
      int limit,
      Optional<String> cursor) {
    if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
    if (kinds.isEmpty()) return new Page(List.of(), Optional.empty());
    var rows =
        jdbc.sql(
                """
        SELECT r.external_id, r.resource_type, r.source_version, r.order_scheme, r.order_position,
               r.source_created_at, r.source_updated_at, r.observed_at, r.metadata,
               a.status AS acl_status, a.keys AS acl_keys, a.observed_at AS acl_observed_at, a.reason AS acl_reason
        """
                    + READ_FROM
                    + " "
                    + """
        WHERE r.source_instance_id = :source AND r.state = 'active'
          AND r.resource_type = ANY(:kinds)
          AND (CAST(:cursor AS text) IS NULL OR r.external_id COLLATE "C" > :cursor)
        """
                    + (cutoff.isPresent() ? EXPIRY_CANDIDATES : "")
                    + " ORDER BY r.external_id COLLATE \"C\" LIMIT :limit")
            .param("source", source.value())
            .param("kinds", kinds.toArray(String[]::new))
            .param("cursor", cursor.orElse(null), Types.VARCHAR)
            .param("limit", limit + 1);
    cutoff.ifPresent(at -> rows.param("cutoff", Timestamp.from(at)));
    List<RawItem> found =
        rows.query(
                (rs, row) ->
                    new RawItem(
                        rs.getString("external_id"),
                        rs.getString("resource_type"),
                        rs.getString("source_version"),
                        rs.getString("order_scheme") == null
                            ? Optional.empty()
                            : Optional.of(
                                new SourceOrder(
                                    rs.getString("order_scheme"), rs.getString("order_position"))),
                        Optional.ofNullable(rs.getString("source_created_at")),
                        Optional.ofNullable(rs.getString("source_updated_at")),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("metadata"),
                        Optional.empty(),
                        rs.getString("acl_status") == null
                            ? RawAcl.notObserved()
                            : PostgresRawAclStore.observed(rs, "acl_")))
            .list();
    if (found.size() <= limit) return new Page(found, Optional.empty());
    return new Page(found.subList(0, limit), Optional.of(found.get(limit - 1).externalId()));
  }

  // 문서 저장 상태를 함께 읽어, 닫힘 이후 지연된 OK 쓰기도 다음 sweep에서 다시 제한한다.
  private static final String EXPIRY_CANDIDATES =
      """
      AND a.observed_at <= :cutoff
      AND (a.status <> 'failed' OR a.reason <> 'EXPIRED'
           OR r.access_expired_at IS DISTINCT FROM a.observed_at
           OR EXISTS (SELECT 1 FROM document_acl d
                      WHERE d.space_id = r.access_expiry_space
                        AND d.document_id = r.access_expiry_document AND d.status <> 'failed'
                        AND NOT EXISTS (SELECT 1 FROM document_spaces o
                                        WHERE o.document_id = d.document_id
                                          AND (o.source_id <> r.source_instance_id OR o.space_id <> d.space_id))))
      """;

  @Override
  public void completeAccessExpiry(
      SourceInstanceId source,
      String externalId,
      Instant observedAt,
      SpaceId space,
      Optional<String> document) {
    jdbc.sql(
            """
        UPDATE raw_items r SET access_expired_at = :observed,
               access_expiry_space = :space, access_expiry_document = :document
        WHERE r.source_instance_id = :source AND r.external_id = :id
          AND (r.access_expired_at IS DISTINCT FROM :observed
               OR r.access_expiry_space IS DISTINCT FROM :space
               OR r.access_expiry_document IS DISTINCT FROM :document)
          AND EXISTS (SELECT 1 FROM raw_acl a
                      WHERE a.source_instance_id = r.source_instance_id AND a.external_id = r.external_id
                        AND a.status = 'failed' AND a.reason = 'EXPIRED' AND a.observed_at = :observed)
        """)
        .param("source", source.value())
        .param("id", externalId)
        .param("observed", Timestamp.from(observedAt))
        .param("space", space.value())
        .param("document", document.orElse(null), Types.VARCHAR)
        .update();
  }

  @Override
  public Outcome apply(SourceInstanceId source, RawMutation mutation) {
    Optional<RawRecord> existing =
        jdbc.sql(
                "SELECT "
                    + READ_COLUMNS
                    + READ_FROM
                    + " WHERE r.source_instance_id = :source AND r.external_id = :id FOR UPDATE OF r")
            .param("source", source.value())
            .param("id", mutation.externalId())
            .query(PostgresRawItemStore::map)
            .optional();
    MutationDecision decision = MutationDecision.of(existing, mutation);
    switch (decision) {
      case MutationDecision.Unchanged unchanged -> {}
      case MutationDecision.WriteActive write -> writeActive(source, write.item());
      case MutationDecision.WriteTombstone write -> writeTombstone(source, write.tombstone());
    }
    return decision.outcome();
  }

  private void writeActive(SourceInstanceId source, RawItem item) {
    blobs.store(item.content(), item.observedAt());
    BlobReferences.translating(
        () ->
            ContentColumns.RAW_ITEM
                .bind(
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
                        .param(
                            "position", item.sourceOrder().map(SourceOrder::position).orElse(null))
                        .param("created", item.sourceCreatedAt().orElse(null))
                        .param("updated", item.sourceUpdatedAt().orElse(null))
                        .param("observed", Timestamp.from(item.observedAt()))
                        .param("metadata", item.metadataJson()),
                    item.content())
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
    deleteAcls(source, List.of(t.externalId()));
  }

  @Override
  public Optional<RawRecord> get(SourceInstanceId source, String externalId) {
    return jdbc.sql(
            "SELECT "
                + READ_COLUMNS
                + READ_FROM
                + " WHERE r.source_instance_id = :source AND r.external_id = :id")
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
                    + READ_COLUMNS
                    + READ_FROM
                    + " WHERE r.source_instance_id = :source AND r.state = 'active'"
                    + " AND (CAST(:cursor AS text) IS NULL OR r.external_id COLLATE \"C\" > CAST(:cursor AS text)) ORDER BY r.external_id COLLATE \"C\" LIMIT :limit")
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
    List<String> removed =
        jdbc.sql(
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
    deleteAcls(source, removed);
    return removed;
  }

  @Override
  public List<String> tombstoneSelected(SourceInstanceId source, Set<String> ids, Instant at) {
    if (ids.isEmpty()) return List.of();
    List<String> removed =
        jdbc.sql(
                """
        UPDATE raw_items SET state = 'deleted', order_scheme = NULL, order_position = NULL,
          source_created_at = NULL, source_updated_at = NULL, observed_at = :at, metadata = NULL,
          content_storage = NULL, content_media_type = NULL, content_inline = NULL,
          content_sha256 = NULL, content_size = NULL, deleted_at = :at
        WHERE source_instance_id = :source AND state = 'active' AND external_id = ANY(:ids)
        RETURNING external_id
        """)
            .param("source", source.value())
            .param("ids", ids.toArray(String[]::new))
            .param("at", Timestamp.from(at))
            .query(String.class)
            .list();
    deleteAcls(source, removed);
    return removed;
  }

  private void deleteAcls(SourceInstanceId source, List<String> ids) {
    if (ids.isEmpty()) return;
    acls.delete(source, ids);
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
    Optional<RawContent> content = ContentColumns.RAW_ITEM.read(rs);
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
            content,
            rs.getString("acl_status") == null
                ? RawAcl.notObserved()
                : PostgresRawAclStore.observed(rs, "acl_")));
  }
}
