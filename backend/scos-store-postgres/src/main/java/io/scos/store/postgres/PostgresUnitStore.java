package io.scos.store.postgres;

import io.scos.spi.store.AccessScope;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
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

/**
 * PostgreSQL 유닛 저장소 — 계약은 UnitStoreContract. 공간별 SQL은 space_id 조건을 가진다(CONTRACTS A1). 전체 공간 압축 개수는
 * 기동 검사 전용이다.
 */
public final class PostgresUnitStore implements UnitStore {
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

  long countCompacted() {
    return jdbc.sql("SELECT count(*) FROM units WHERE tier = 'compacted'")
        .query(Long.class)
        .single();
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    Array a = rs.getArray(column);
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }

  /**
   * 배치 적재: 소유 확인(한 문장) 뒤 유닛마다 같은 upsert 문장을 JDBC batch 하나로 보낸다(왕복 1회). 문장이 행마다 따로 실행되므로 같은 id가 배치에
   * 다시 나와도 행 단위 upsert와 결과가 같다(마지막 내용, 처음 seq).
   */
  void upsert(SpaceId space, List<ContextUnit> units) {
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
   * 직렬화된다(DO NOTHING + SELECT는 문장 스냅샷 때문에 방금 커밋된 행을 못 봤다).
   */
  private void claimOwnership(SpaceId space, List<ContextUnit> units) {
    Map<String, String> sourcesByDocument = new LinkedHashMap<>();
    for (ContextUnit unit : units)
      sourcesByDocument.putIfAbsent(unit.documentId(), unit.provenance().sourceId());
    String[] docs = sourcesByDocument.keySet().toArray(String[]::new);
    if (docs.length == 0) return;
    List<OwnerRow> owners =
        jdbc.sql(
                """
            INSERT INTO document_spaces (document_id, space_id, source_id)
            SELECT d, :space, s FROM unnest(:docs, :sources) AS t(d, s)
            ON CONFLICT (document_id) DO UPDATE SET space_id = document_spaces.space_id
            RETURNING document_id, space_id
            """)
            .param("space", space.value())
            .param("docs", docs)
            .param("sources", sourcesByDocument.values().toArray(String[]::new))
            .query((rs, i) -> new OwnerRow(rs.getString(1), rs.getString(2)))
            .list();
    for (OwnerRow row : owners)
      if (!row.spaceId().equals(space.value()))
        throw new SpaceOwnershipException(row.documentId(), new SpaceId(row.spaceId()), space);
  }

  @Override
  public List<ContextUnit> getMany(SpaceId space, AccessScope access, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    var statement = UnitReadSql.getMany(space, access, ids);
    return readUnits(statement, ids);
  }

  List<ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    return readUnits(UnitReadSql.previousUnits(space, ids), ids);
  }

  private List<ContextUnit> readUnits(UnitReadSql.Statement statement, List<String> ids) {
    Map<String, ContextUnit> byId =
        jdbc.sql(statement.sql()).params(statement.params()).query(UNIT).list().stream()
            .collect(Collectors.toMap(ContextUnit::id, Function.identity()));
    return inRequestedOrder(ids, byId);
  }

  @Override
  public List<ContextUnit> listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range) {
    var statement = UnitReadSql.listByDocument(space, access, documentId, range);
    return jdbc.sql(statement.sql()).params(statement.params()).query(UNIT).list();
  }

  @Override
  public List<LinkedUnit> linkedUnits(SpaceId space, AccessScope access, List<String> unitIds) {
    if (unitIds.isEmpty()) return List.of();
    var statement = UnitReadSql.linkedUnits(space, access, unitIds);
    return jdbc.sql(statement.sql())
        .params(statement.params())
        .query((rs, i) -> new LinkedUnit(rs.getString("id"), rs.getString("document_id")))
        .list();
  }

  @Override
  public List<DocumentInfo> getDocuments(
      SpaceId space, AccessScope access, List<String> documentIds) {
    if (documentIds.isEmpty()) return List.of();
    var statement = UnitReadSql.getDocuments(space, access, documentIds);
    Map<String, DocumentInfo> byDoc =
        jdbc
            .sql(statement.sql())
            .params(statement.params())
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
    return inRequestedOrder(documentIds, byDoc);
  }

  Optional<String> latestCapturedAt(SpaceId space) {
    var statement = UnitReadSql.latestCapturedAt(space);
    return jdbc.sql(statement.sql()).params(statement.params()).query(String.class).optional();
  }

  List<String> deleteByDocument(
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
        .query((rs, i) -> new DeletedUnit(rs.getString("id"), rs.getLong("seq")))
        .list()
        .stream()
        .sorted(Comparator.comparingLong(DeletedUnit::seq))
        .map(DeletedUnit::id)
        .toList();
  }

  List<String> listDocumentIds(SpaceId space, String sourceId) {
    return jdbc.sql(
            "SELECT document_id FROM units WHERE space_id = :space AND source_id = :source GROUP BY document_id ORDER BY min(seq)")
        .param("space", space.value())
        .param("source", sourceId)
        .query(String.class)
        .list();
  }

  List<ContextUnit> listAll(SpaceId space) {
    return jdbc.sql(
            "SELECT " + UnitReadSql.COLUMNS + " FROM units WHERE space_id = :space ORDER BY seq")
        .param("space", space.value())
        .query(UNIT)
        .list();
  }

  /** 문서 소유 확인이 돌려준 행: 문서와 그 현재 소유 공간. */
  private record OwnerRow(String documentId, String spaceId) {}

  /** 지운 유닛과 적재 순서. */
  private record DeletedUnit(String id, long seq) {}

  /** 요청한 키 순서대로, 찾은 것만. */
  private static <T> List<T> inRequestedOrder(List<String> keys, Map<String, T> byKey) {
    List<T> out = new ArrayList<>();
    for (String key : keys) if (byKey.containsKey(key)) out.add(byKey.get(key));
    return out;
  }
}
