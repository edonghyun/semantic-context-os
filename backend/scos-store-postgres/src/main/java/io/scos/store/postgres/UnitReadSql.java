package io.scos.store.postgres;

import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore.Range;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 유닛 읽기 문장과 이름 바인드는 결과 매핑·조회 실행과 독립적으로 고정한다. */
final class UnitReadSql {
  static final String COLUMNS =
      "id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri, captured_at, importance, valid_from, invalidated_at, links, tags, aliases, compacted_from";

  record Statement(String sql, Map<String, Object> params) {}

  private UnitReadSql() {}

  static Statement getMany(SpaceId space, AccessScope access, List<String> ids) {
    Statement stored = unitsById(space, ids);
    String sql = stored.sql();
    Map<String, Object> params = stored.params();
    if (access instanceof AccessScope.Restricted restricted) {
      sql += " AND " + acl("units", "units.space_id");
      params = restrictedParams(params, restricted);
    }
    return new Statement(sql, params);
  }

  /** 적재 diff의 이전 유닛 조회. 요청 범위를 만드는 읽기 API와 구분한다. */
  static Statement previousUnits(SpaceId space, List<String> ids) {
    return unitsById(space, ids);
  }

  private static Statement unitsById(SpaceId space, List<String> ids) {
    return new Statement(
        "SELECT " + COLUMNS + " FROM units WHERE space_id = :space AND id = ANY(:ids)",
        Map.of("space", space.value(), "ids", ids.toArray(String[]::new)));
  }

  static Statement listByDocument(
      SpaceId space, AccessScope access, String documentId, Range range) {
    String sql = "SELECT " + COLUMNS + " FROM units WHERE space_id = :space AND document_id = :doc";
    Map<String, Object> params =
        Map.of(
            "space", space.value(),
            "doc", documentId,
            "offset", range.offset(),
            "limit", range.limit());
    if (access instanceof AccessScope.Restricted restricted) {
      sql += " AND units.tier <> 'compacted' AND " + acl("units", "units.space_id");
      params = restrictedParams(params, restricted);
    }
    return new Statement(sql + " ORDER BY seq OFFSET :offset LIMIT :limit", params);
  }

  static Statement linkedUnits(SpaceId space, AccessScope access, List<String> unitIds) {
    String sql =
        """
        SELECT t.id, t.document_id FROM units t
        WHERE t.space_id = :space
          AND t.document_id = ANY(SELECT unnest(o.links) FROM units o WHERE o.space_id = :space AND o.id = ANY(:ids))
          AND NOT (t.id = ANY(:ids))
        """;
    Map<String, Object> params =
        Map.of("space", space.value(), "ids", unitIds.toArray(String[]::new));
    if (access instanceof AccessScope.Restricted restricted) {
      sql += " AND " + acl("t", "t.space_id") + "\n";
      params = restrictedParams(params, restricted);
    }
    return new Statement(sql + "ORDER BY t.seq\n", params);
  }

  static Statement getDocuments(SpaceId space, AccessScope access, List<String> documentIds) {
    String sql =
        """
        SELECT s.document_id, f.title, f.uri, f.source_id, s.unit_count, s.latest
        FROM (SELECT document_id, count(*) AS unit_count, max(NULLIF(captured_at, '') COLLATE "C") AS latest
              FROM units WHERE space_id = :space AND document_id = ANY(:docs) GROUP BY document_id) s
        JOIN LATERAL (SELECT title, uri, source_id FROM units u WHERE u.space_id = :space AND u.document_id = s.document_id ORDER BY seq LIMIT 1) f ON TRUE
        """;
    Map<String, Object> params =
        Map.of("space", space.value(), "docs", documentIds.toArray(String[]::new));
    if (access instanceof AccessScope.Restricted restricted) {
      sql += "WHERE " + acl("s", ":space");
      params = restrictedParams(params, restricted);
    }
    return new Statement(sql, params);
  }

  private static String acl(String row, String space) {
    return "EXISTS (SELECT 1 FROM document_acl a WHERE a.space_id = "
        + space
        + " AND a.document_id = "
        + row
        + ".document_id"
        + " AND a.status IN ('ok','unsupported') AND a.keys && CAST(:keys AS text[]))";
  }

  private static Map<String, Object> restrictedParams(
      Map<String, Object> params, AccessScope.Restricted access) {
    var out = new HashMap<>(params);
    out.put("keys", access.keys().stream().map(AccessKey::toString).toArray(String[]::new));
    return Map.copyOf(out);
  }

  static Statement latestCapturedAt(SpaceId space) {
    // COLLATE "C" = 바이트 순(UTF-8은 코드포인트 순 = BMP에서 UTF-16 순) — SQLite BINARY와 같다
    return new Statement(
        "SELECT max(NULLIF(captured_at, '') COLLATE \"C\") FROM units WHERE space_id = :space",
        Map.of("space", space.value()));
  }
}
