package io.scos.store.postgres;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Provenance;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL 유닛 저장소 — 계약은 UnitStoreContract. 모든 SQL이 space_id 조건을 가진다(CONTRACTS A1). */
public final class PostgresUnitStore implements UnitStore {
  private static final String COLUMNS =
      "id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri, captured_at, importance, valid_from, invalidated_at, links, tags, aliases, compacted_from";
  private static final RowMapper<ContextUnit> UNIT =
      (rs, i) ->
          new ContextUnit(
              rs.getString("id"),
              rs.getString("partition"),
              rs.getString("title"),
              rs.getString("content"),
              rs.getString("content_hash"),
              rs.getInt("tokens"),
              Tier.fromWire(rs.getString("tier")),
              new Provenance(
                  rs.getString("source_id"),
                  rs.getString("document_id"),
                  rs.getString("uri"),
                  rs.getString("captured_at")),
              rs.getInt("importance"),
              Optional.ofNullable(rs.getString("valid_from")),
              Optional.ofNullable(rs.getString("invalidated_at")),
              strings(rs, "links"),
              strings(rs, "tags"),
              strings(rs, "aliases"),
              Optional.empty(),
              strings(rs, "compacted_from"));

  private final JdbcClient jdbc;
  private final NamedParameterJdbcOperations batch;

  public PostgresUnitStore(NamedParameterJdbcTemplate template) {
    this.jdbc = JdbcClient.create(template);
    this.batch = template;
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    Array a = rs.getArray(column);
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }

  /**
   * M-11 배치 적재: 소유 확인(한 문장) 뒤 유닛마다 같은 upsert 문장을 JDBC batch 하나로 보낸다(왕복 1회). 문장이 행마다 따로 실행되므로 같은 id가
   * 배치에 다시 나와도 행 단위 upsert와 결과가 같다(마지막 내용, 처음 seq).
   */
  @Override
  public void upsert(SpaceId space, List<ContextUnit> units) {
    claimOwnership(space, units);
    if (units.isEmpty()) return;
    batch.batchUpdate(
        """
              INSERT INTO units (space_id, id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri, captured_at, importance,
                                 valid_from, invalidated_at, links, tags, aliases, compacted_from)
              VALUES (:space, :id, :doc, :partition, :title, :content, :hash, :tokens, :tier, :source, :uri, :captured, :importance,
                      :validFrom, :invalidatedAt, :links, :tags, :aliases, :compactedFrom)
              ON CONFLICT (space_id, id) DO UPDATE SET
                document_id = EXCLUDED.document_id, partition = EXCLUDED.partition, title = EXCLUDED.title, content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash, tokens = EXCLUDED.tokens, tier = EXCLUDED.tier, source_id = EXCLUDED.source_id, uri = EXCLUDED.uri,
                captured_at = EXCLUDED.captured_at, importance = EXCLUDED.importance, valid_from = EXCLUDED.valid_from, invalidated_at = EXCLUDED.invalidated_at,
                links = EXCLUDED.links, tags = EXCLUDED.tags, aliases = EXCLUDED.aliases, compacted_from = EXCLUDED.compacted_from
              """,
        units.stream()
            .map(
                u ->
                    new MapSqlParameterSource()
                        .addValue("space", space.value())
                        .addValue("id", u.id())
                        .addValue("doc", u.documentId())
                        .addValue("partition", u.partition())
                        .addValue("title", u.title())
                        .addValue("content", u.content())
                        .addValue("hash", u.contentHash())
                        .addValue("tokens", u.tokens())
                        .addValue("tier", u.tier().wire())
                        .addValue("source", u.provenance().sourceId())
                        .addValue("uri", u.provenance().uri())
                        .addValue("captured", u.provenance().capturedAt())
                        .addValue("importance", u.importance())
                        .addValue("validFrom", u.validFrom().orElse(null), Types.VARCHAR)
                        .addValue("invalidatedAt", u.invalidatedAt().orElse(null), Types.VARCHAR)
                        .addValue("links", u.links().toArray(String[]::new))
                        .addValue("tags", u.tags().toArray(String[]::new))
                        .addValue("aliases", u.aliases().toArray(String[]::new))
                        .addValue("compactedFrom", u.compactedFrom().toArray(String[]::new)))
            .toArray(SqlParameterSource[]::new));
  }

  /**
   * A2: 배치의 모든 문서를 한 문장으로 먼저 확인한다 — 첫 기록자가 소유, 불일치는 계약 위반(아무것도 쓰기 전에 거부). `ON CONFLICT DO UPDATE
   * SET space_id = document_spaces.space_id RETURNING`은 충돌 행을 잠그고 현재 소유자를 돌려주므로 동시 경합에서도 한 문장으로
   * 직렬화된다(DO NOTHING + SELECT는 문장 스냅샷 때문에 방금 커밋된 행을 못 봤다 — 재검토 N-4).
   */
  private void claimOwnership(SpaceId space, List<ContextUnit> units) {
    String[] docs = units.stream().map(ContextUnit::documentId).distinct().toArray(String[]::new);
    if (docs.length == 0) return;
    List<String[]> owners =
        jdbc.sql(
                """
            INSERT INTO document_spaces (document_id, space_id)
            SELECT d, :space FROM unnest(:docs) AS t(d)
            ON CONFLICT (document_id) DO UPDATE SET space_id = document_spaces.space_id
            RETURNING document_id, space_id
            """)
            .param("space", space.value())
            .param("docs", docs)
            .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
            .list();
    for (String[] row : owners)
      if (!row[1].equals(space.value()))
        throw new SpaceOwnershipException(row[0], new SpaceId(row[1]), space);
  }

  @Override
  public List<ContextUnit> getMany(SpaceId space, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    Map<String, ContextUnit> byId =
        jdbc
            .sql("SELECT " + COLUMNS + " FROM units WHERE space_id = :space AND id = ANY(:ids)")
            .param("space", space.value())
            .param("ids", ids.toArray(String[]::new))
            .query(UNIT)
            .list()
            .stream()
            .collect(Collectors.toMap(ContextUnit::id, Function.identity()));
    List<ContextUnit> out = new ArrayList<>();
    for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
    return out;
  }

  @Override
  public List<ContextUnit> listByDocument(SpaceId space, String documentId, Range range) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM units WHERE space_id = :space AND document_id = :doc ORDER BY seq OFFSET :offset LIMIT :limit")
        .param("space", space.value())
        .param("doc", documentId)
        .param("offset", range.offset())
        .param("limit", range.limit())
        .query(UNIT)
        .list();
  }

  @Override
  public List<LinkedUnit> linkedUnits(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return List.of();
    String[] ids = unitIds.toArray(String[]::new);
    return jdbc.sql(
            """
            SELECT t.id, t.document_id FROM units t
            WHERE t.space_id = :space
              AND t.document_id = ANY(SELECT unnest(o.links) FROM units o WHERE o.space_id = :space AND o.id = ANY(:ids))
              AND NOT (t.id = ANY(:ids))
            ORDER BY t.seq
            """)
        .param("space", space.value())
        .param("ids", ids)
        .query((rs, i) -> new LinkedUnit(rs.getString("id"), rs.getString("document_id")))
        .list();
  }

  @Override
  public List<DocumentInfo> getDocuments(SpaceId space, List<String> documentIds) {
    if (documentIds.isEmpty()) return List.of();
    Map<String, DocumentInfo> byDoc =
        jdbc
            .sql(
                """
            SELECT s.document_id, f.title, f.uri, f.source_id, s.unit_count, s.latest
            FROM (SELECT document_id, count(*) AS unit_count, max(NULLIF(captured_at, '') COLLATE "C") AS latest
                  FROM units WHERE space_id = :space AND document_id = ANY(:docs) GROUP BY document_id) s
            JOIN LATERAL (SELECT title, uri, source_id FROM units u WHERE u.space_id = :space AND u.document_id = s.document_id ORDER BY seq LIMIT 1) f ON TRUE
            """)
            .param("space", space.value())
            .param("docs", documentIds.toArray(String[]::new))
            .query(
                (rs, i) ->
                    new DocumentInfo(
                        rs.getString("document_id"),
                        rs.getString("title"),
                        rs.getString("uri"),
                        rs.getString("source_id"),
                        rs.getInt("unit_count"),
                        Optional.ofNullable(rs.getString("latest"))))
            .list()
            .stream()
            .collect(Collectors.toMap(DocumentInfo::documentId, Function.identity()));
    List<DocumentInfo> out = new ArrayList<>();
    for (String doc : documentIds) if (byDoc.containsKey(doc)) out.add(byDoc.get(doc));
    return out;
  }

  @Override
  public Optional<String> latestCapturedAt(SpaceId space) {
    // COLLATE "C" = 바이트 순(UTF-8은 코드포인트 순 = BMP에서 UTF-16 순) — TS·SQLite BINARY와 같다(리뷰 M-2)
    return jdbc.sql(
            "SELECT max(NULLIF(captured_at, '') COLLATE \"C\") FROM units WHERE space_id = :space")
        .param("space", space.value())
        .query(String.class)
        .optional();
  }

  @Override
  public List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    return jdbc
        .sql(
            """
            DELETE FROM units WHERE space_id = :space AND source_id = :source AND document_id = :doc AND NOT (id = ANY(:keep))
            RETURNING id, seq
            """)
        .param("space", space.value())
        .param("source", sourceId)
        .param("doc", documentId)
        .param("keep", keepIds.toArray(String[]::new))
        .query((rs, i) -> new Object[] {rs.getString("id"), rs.getLong("seq")})
        .list()
        .stream()
        .sorted(java.util.Comparator.comparingLong(r -> (Long) r[1]))
        .map(r -> (String) r[0])
        .toList();
  }

  @Override
  public List<String> listDocumentIds(SpaceId space, String sourceId) {
    return jdbc.sql(
            "SELECT document_id FROM units WHERE space_id = :space AND source_id = :source GROUP BY document_id ORDER BY min(seq)")
        .param("space", space.value())
        .param("source", sourceId)
        .query(String.class)
        .list();
  }

  @Override
  public List<ContextUnit> listAll(SpaceId space) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM units WHERE space_id = :space ORDER BY seq")
        .param("space", space.value())
        .query(UNIT)
        .list();
  }
}
