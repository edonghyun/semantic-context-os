package io.scos.store.sqlite;

import static io.scos.store.sqlite.SqlParts.aclFilter;
import static io.scos.store.sqlite.SqlParts.bindKeys;
import static io.scos.store.sqlite.SqlParts.json;
import static io.scos.store.sqlite.SqlParts.strings;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Provenance;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceOwnershipException;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SQLite 유닛 저장소(V1 {@code units}·{@code document_spaces}). 목록 열은 JSON 배열 텍스트이고, id·키 목록은 JSON 한
 * 파라미터로 넘겨 {@code json_each}로 푼다(변수 수 한도가 없다). 공간별 연산의 SQL은 space_id 조건을 가진다(CONTRACTS A1).
 */
public final class SqliteUnitStore implements UnitStore {
  private static final String COLUMNS =
      "id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri,"
          + " captured_at, importance, valid_from, invalidated_at, links, tags, aliases, compacted_from";

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
              strings(rs.getString("links")),
              strings(rs.getString("tags")),
              strings(rs.getString("aliases")),
              Optional.empty(),
              strings(rs.getString("compacted_from")));

  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public SqliteUnitStore(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  long countCompacted() {
    return jdbc.sql("SELECT count(*) FROM units WHERE tier = 'compacted'")
        .query(Long.class)
        .single();
  }

  /**
   * 배치 적재: 소유 확인 뒤 유닛마다 upsert 한다. 같은 id가 배치에 다시 나와도 행 단위 upsert와 결과가 같다(마지막 내용, 처음 seq). 호출자의
   * 트랜잭션에 참여하고, 없으면 한 트랜잭션으로 묶는다(소유 거부는 아무것도 남기지 않는다).
   */
  void upsert(SpaceId space, List<ContextUnit> units) {
    if (units.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          claimOwnership(space, units);
          for (ContextUnit u : units) upsertOne(space, u);
        });
  }

  private void upsertOne(SpaceId space, ContextUnit u) {
    jdbc.sql(
            """
            INSERT INTO units (space_id, id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri, captured_at, importance,
                               valid_from, invalidated_at, links, tags, aliases, compacted_from)
            VALUES (:space, :id, :doc, :partition, :title, :content, :hash, :tokens, :tier, :source, :uri, :captured, :importance,
                    :validFrom, :invalidatedAt, :links, :tags, :aliases, :compactedFrom)
            ON CONFLICT (space_id, id) DO UPDATE SET
              document_id = excluded.document_id, partition = excluded.partition, title = excluded.title, content = excluded.content,
              content_hash = excluded.content_hash, tokens = excluded.tokens, tier = excluded.tier, source_id = excluded.source_id, uri = excluded.uri,
              captured_at = excluded.captured_at, importance = excluded.importance, valid_from = excluded.valid_from, invalidated_at = excluded.invalidated_at,
              links = excluded.links, tags = excluded.tags, aliases = excluded.aliases, compacted_from = excluded.compacted_from
            """)
        .param("space", space.value())
        .param("id", u.id())
        .param("doc", u.documentId())
        .param("partition", u.partition())
        .param("title", u.title())
        .param("content", u.content())
        .param("hash", u.contentHash())
        .param("tokens", u.tokens())
        .param("tier", u.tier().wire())
        .param("source", u.provenance().sourceId())
        .param("uri", u.provenance().uri())
        .param("captured", u.provenance().capturedAt())
        .param("importance", u.importance())
        .param("validFrom", u.validFrom().orElse(null))
        .param("invalidatedAt", u.invalidatedAt().orElse(null))
        .param("links", json(u.links()))
        .param("tags", json(u.tags()))
        .param("aliases", json(u.aliases()))
        .param("compactedFrom", json(u.compactedFrom()))
        .update();
  }

  /** A2·A3: 배치의 공간·출처 소유가 모두 일치해야 유닛을 쓴다. 호출자의 트랜잭션이 신규 소유 등록까지 함께 롤백한다. */
  private void claimOwnership(SpaceId space, List<ContextUnit> units) {
    Map<String, String> sourcesByDocument = new LinkedHashMap<>();
    for (ContextUnit unit : units) {
      String prior = sourcesByDocument.putIfAbsent(unit.documentId(), unit.provenance().sourceId());
      if (prior != null && !prior.equals(unit.provenance().sourceId()))
        throw new IllegalArgumentException("다른 출처 소유 문서의 쓰기 거부: " + unit.documentId());
    }
    List<Map<String, String>> claims =
        sourcesByDocument.entrySet().stream()
            .map(e -> Map.of("d", e.getKey(), "s", e.getValue()))
            .toList();
    List<OwnerRow> owners =
        jdbc.sql(
                """
                INSERT INTO document_spaces (document_id, space_id, source_id)
                SELECT json_extract(value, '$.d'), :space, json_extract(value, '$.s') FROM json_each(:claims) WHERE true
                ON CONFLICT (document_id) DO UPDATE SET space_id = document_spaces.space_id
                RETURNING document_id, space_id, source_id
                """)
            .param("space", space.value())
            .param("claims", json(claims))
            .query((rs, i) -> new OwnerRow(rs.getString(1), rs.getString(2), rs.getString(3)))
            .list();
    for (OwnerRow row : owners) {
      if (!row.spaceId().equals(space.value()))
        throw new SpaceOwnershipException(row.documentId(), new SpaceId(row.spaceId()), space);
      if (!row.sourceId().equals(sourcesByDocument.get(row.documentId())))
        throw new IllegalArgumentException("다른 출처 소유 문서의 쓰기 거부: " + row.documentId());
    }
  }

  @Override
  public List<ContextUnit> getMany(SpaceId space, AccessScope access, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    StatementSpec statement =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM units WHERE space_id = :space AND id IN (SELECT value FROM json_each(:ids))"
                    + (restricted(access) ? aclFilter("units.document_id") : ""))
            .param("space", space.value())
            .param("ids", json(ids));
    return inRequestedOrder(ids, byId(bindKeys(statement, access).query(UNIT).list()));
  }

  List<ContextUnit> previousUnits(SpaceId space, List<String> ids) {
    if (ids.isEmpty()) return List.of();
    List<ContextUnit> found =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM units WHERE space_id = :space AND id IN (SELECT value FROM json_each(:ids))")
            .param("space", space.value())
            .param("ids", json(ids))
            .query(UNIT)
            .list();
    return inRequestedOrder(ids, byId(found));
  }

  @Override
  public List<ContextUnit> listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range) {
    StatementSpec statement =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM units WHERE space_id = :space AND document_id = :doc"
                    + (restricted(access)
                        ? " AND tier <> 'compacted'" + aclFilter("units.document_id")
                        : "")
                    + " ORDER BY seq LIMIT :limit OFFSET :offset")
            .param("space", space.value())
            .param("doc", documentId)
            .param("limit", range.limit())
            .param("offset", range.offset());
    return bindKeys(statement, access).query(UNIT).list();
  }

  @Override
  public List<LinkedUnit> linkedUnits(SpaceId space, AccessScope access, List<String> unitIds) {
    if (unitIds.isEmpty()) return List.of();
    StatementSpec statement =
        jdbc.sql(
                """
                SELECT t.id, t.document_id FROM units t
                WHERE t.space_id = :space
                  AND t.document_id IN (SELECT l.value FROM units o, json_each(o.links) l
                                        WHERE o.space_id = :space AND o.id IN (SELECT value FROM json_each(:ids)))
                  AND t.id NOT IN (SELECT value FROM json_each(:ids))
                """
                    + (restricted(access) ? aclFilter("t.document_id") : "")
                    + " ORDER BY t.seq")
            .param("space", space.value())
            .param("ids", json(unitIds));
    return bindKeys(statement, access)
        .query((rs, i) -> new LinkedUnit(rs.getString("id"), rs.getString("document_id")))
        .list();
  }

  @Override
  public List<DocumentInfo> getDocuments(
      SpaceId space, AccessScope access, List<String> documentIds) {
    if (documentIds.isEmpty()) return List.of();
    StatementSpec statement =
        jdbc.sql(
                """
                SELECT s.document_id, f.title, f.uri, f.source_id, s.unit_count, s.latest
                FROM (SELECT document_id, count(*) AS unit_count, max(NULLIF(captured_at, '')) AS latest
                      FROM units WHERE space_id = :space AND document_id IN (SELECT value FROM json_each(:docs))
                      GROUP BY document_id) s
                JOIN units f ON f.seq = (SELECT min(u.seq) FROM units u WHERE u.space_id = :space AND u.document_id = s.document_id)
                WHERE true
                """
                    + (restricted(access) ? aclFilter("s.document_id") : ""))
            .param("space", space.value())
            .param("docs", json(documentIds));
    Map<String, DocumentInfo> byDoc =
        bindKeys(statement, access)
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
    return jdbc.sql("SELECT max(NULLIF(captured_at, '')) FROM units WHERE space_id = :space")
        .param("space", space.value())
        .query(String.class)
        .optional();
  }

  List<String> deleteByDocument(
      SpaceId space, String sourceId, String documentId, List<String> keepIds) {
    List<DeletedUnit> deleted =
        jdbc.sql(
                """
                DELETE FROM units WHERE space_id = :space AND source_id = :source AND document_id = :doc
                  AND id NOT IN (SELECT value FROM json_each(:keep))
                RETURNING id, seq
                """)
            .param("space", space.value())
            .param("source", sourceId)
            .param("doc", documentId)
            .param("keep", json(keepIds))
            .query((rs, i) -> new DeletedUnit(rs.getString("id"), rs.getLong("seq")))
            .list();
    return deleted.stream()
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
    return jdbc.sql("SELECT " + COLUMNS + " FROM units WHERE space_id = :space ORDER BY seq")
        .param("space", space.value())
        .query(UNIT)
        .list();
  }

  private static boolean restricted(AccessScope access) {
    return access instanceof AccessScope.Restricted;
  }

  private static Map<String, ContextUnit> byId(List<ContextUnit> units) {
    return units.stream().collect(Collectors.toMap(ContextUnit::id, Function.identity()));
  }

  /** 문서 소유 확인이 돌려준 행: 문서와 그 현재 소유 공간·출처. */
  private record OwnerRow(String documentId, String spaceId, String sourceId) {}

  /** 지운 유닛과 적재 순서. */
  private record DeletedUnit(String id, long seq) {}

  /** 요청한 키 순서대로, 찾은 것만. */
  private static <T> List<T> inRequestedOrder(List<String> keys, Map<String, T> byKey) {
    List<T> out = new ArrayList<>();
    for (String key : keys) if (byKey.containsKey(key)) out.add(byKey.get(key));
    return out;
  }
}
