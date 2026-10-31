package io.scos.store.postgres;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * pgvector 정확 검색 어댑터. 점수 = 1 − (embedding <=> query)(코사인 거리의 보수). 영벡터는 pgvector가 NaN 거리를 내고, 차원이 다른
 * 행은 WHERE로 제외한다. 동점은 seq 순. 색인 없이 전체 스캔이라 비용은 행 수에 비례한다.
 */
public final class PostgresVectorIndex implements VectorIndex {
  private final JdbcClient jdbc;
  private final NamedParameterJdbcOperations batch;

  public PostgresVectorIndex(NamedParameterJdbcTemplate template) {
    this.jdbc = JdbcClient.create(template);
    this.batch = template;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public List<List<ScoredRef>> search(
      SpaceId space, List<double[]> queries, int k, SearchFilter filter) {
    List<List<ScoredRef>> out = new ArrayList<>(queries.size());
    for (double[] q : queries) out.add(searchOne(space, q, k, filter));
    return out;
  }

  private List<ScoredRef> searchOne(SpaceId space, double[] query, int k, SearchFilter filter) {
    if (isZero(query)) return List.of();
    var statement = VectorSearchSql.search(space, query, k, filter);
    return jdbc
        .sql(statement.sql())
        .params(statement.params())
        .query(
            (rs, i) ->
                new ScoredRef(
                    rs.getString("unit_id"), rs.getString("document_id"), rs.getDouble("score")))
        .list()
        .stream()
        .filter(r -> r.score() > 0 && !Double.isNaN(r.score()))
        .toList();
  }

  /**
   * 배치 적재: 유닛마다 같은 upsert 문장을 JDBC batch 하나로 보낸다(왕복 1회). 문장이 행마다 따로 실행되므로 같은 unitId가 배치에 다시 나와도 행
   * 단위 upsert와 결과가 같다(마지막 벡터, 처음 seq).
   */
  @Override
  public void upsert(SpaceId space, List<IndexedUnit> units) {
    if (units.isEmpty()) return;
    batch.batchUpdate(
        """
        INSERT INTO unit_vectors (space_id, unit_id, document_id, partition, archived, embedding)
        VALUES (:space, :unit, :doc, :partition, :archived, CAST(:vec AS vector))
        ON CONFLICT (space_id, unit_id) DO UPDATE
          SET document_id = EXCLUDED.document_id, partition = EXCLUDED.partition, archived = EXCLUDED.archived, embedding = EXCLUDED.embedding
        """,
        units.stream()
            .map(
                u ->
                    new MapSqlParameterSource()
                        .addValue("space", space.value())
                        .addValue("unit", u.unitId())
                        .addValue("doc", u.documentId())
                        .addValue("partition", u.partition())
                        .addValue("archived", u.archived())
                        .addValue("vec", PgVectorLiteral.format(u.embedding())))
            .toArray(SqlParameterSource[]::new));
  }

  @Override
  public Map<String, double[]> vectorsOf(SpaceId space, AccessScope access, List<String> unitIds) {
    if (unitIds.isEmpty()) return Map.of();
    return readVectors(VectorSearchSql.vectorsOf(space, access, unitIds));
  }

  Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return Map.of();
    return readVectors(VectorSearchSql.storedVectors(space, unitIds));
  }

  private Map<String, double[]> readVectors(VectorSearchSql.Statement statement) {
    Map<String, double[]> out = new LinkedHashMap<>();
    jdbc.sql(statement.sql())
        .params(statement.params())
        .query(
            (rs, i) -> Map.entry(rs.getString("unit_id"), PgVectorLiteral.parse(rs.getString("v"))))
        .list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  @Override
  public void delete(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return;
    jdbc.sql("DELETE FROM unit_vectors WHERE space_id = :space AND unit_id = ANY(:units)")
        .param("space", space.value())
        .param("units", unitIds.toArray(String[]::new))
        .update();
  }

  private static boolean isZero(double[] v) {
    for (double x : v) if (x != 0) return false;
    return true;
  }
}
