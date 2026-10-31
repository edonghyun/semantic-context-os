package io.scos.store.postgres;

import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 벡터 읽기 문장과 이름 바인드의 조립을 함께 소유한다. */
final class VectorSearchSql {
  private static final String ACL_FILTER =
      """
        AND EXISTS (SELECT 1 FROM document_acl a
                    WHERE a.space_id = v.space_id AND a.document_id = v.document_id
                      AND a.status IN ('ok','unsupported') AND a.keys && CAST(:keys AS text[]))
      """;

  record Statement(String sql, Map<String, Object> params) {}

  private VectorSearchSql() {}

  static Statement search(SpaceId space, double[] query, int k, SearchFilter filter) {
    Map<String, Object> params = searchParams(space, query, k, filter);
    if (filter.access() instanceof AccessScope.Restricted restricted)
      return restrictedSearch(params, restricted);
    return new Statement(
        """
        SELECT unit_id, document_id, 1 - (embedding <=> CAST(:q AS vector)) AS score
        FROM unit_vectors
        WHERE space_id = :space AND vector_dims(embedding) = :dim AND (:archived OR NOT archived)
          AND (:anyPartition OR partition = ANY(:partitions))
        ORDER BY embedding <=> CAST(:q AS vector), seq
        LIMIT :k
        """,
        params);
  }

  private static Statement restrictedSearch(
      Map<String, Object> params, AccessScope.Restricted access) {
    return new Statement(
        """
        SELECT v.unit_id, v.document_id, 1 - (v.embedding <=> CAST(:q AS vector)) AS score
        FROM unit_vectors v
        WHERE v.space_id = :space AND vector_dims(v.embedding) = :dim AND (:archived OR NOT v.archived)
          AND (:anyPartition OR v.partition = ANY(:partitions))
        """
            + ACL_FILTER
            + """
        ORDER BY v.embedding <=> CAST(:q AS vector), v.seq
        LIMIT :k
        """,
        restrictedParams(params, access));
  }

  private static Map<String, Object> searchParams(
      SpaceId space, double[] query, int k, SearchFilter filter) {
    return Map.of(
        "q",
        PgVectorLiteral.format(query),
        "space",
        space.value(),
        "dim",
        query.length,
        "archived",
        filter.includeArchived(),
        "anyPartition",
        filter.partitions() == null,
        "partitions",
        filter.partitions() == null ? new String[0] : filter.partitions().toArray(String[]::new),
        "k",
        k);
  }

  static Statement vectorsOf(SpaceId space, AccessScope access, List<String> unitIds) {
    Map<String, Object> params =
        Map.of("space", space.value(), "ids", unitIds.toArray(String[]::new));
    if (access instanceof AccessScope.Restricted restricted) {
      return new Statement(
          """
          SELECT v.unit_id, v.embedding::text AS v FROM unit_vectors v
          WHERE v.space_id = :space AND v.unit_id = ANY(:ids)
          """
              + ACL_FILTER
              + """
          ORDER BY v.seq
          """,
          restrictedParams(params, restricted));
    }
    return vectorsById(space, unitIds);
  }

  /** 적재 재사용용 저장 벡터 조회. 요청자의 범위와 무관한 유지보수 문장이다. */
  static Statement storedVectors(SpaceId space, List<String> unitIds) {
    return vectorsById(space, unitIds);
  }

  private static Statement vectorsById(SpaceId space, List<String> unitIds) {
    return new Statement(
        "SELECT unit_id, embedding::text AS v FROM unit_vectors WHERE space_id = :space AND unit_id = ANY(:ids) ORDER BY seq",
        Map.of("space", space.value(), "ids", unitIds.toArray(String[]::new)));
  }

  private static Map<String, Object> restrictedParams(
      Map<String, Object> params, AccessScope.Restricted access) {
    var out = new HashMap<>(params);
    out.put("keys", access.keys().stream().map(AccessKey::toString).toArray(String[]::new));
    return Map.copyOf(out);
  }
}
