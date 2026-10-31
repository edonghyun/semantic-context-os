package io.scos.store.sqlite;

import static io.scos.store.sqlite.SqlParts.aclFilter;
import static io.scos.store.sqlite.SqlParts.bindKeys;
import static io.scos.store.sqlite.SqlParts.json;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SQLite 정확 코사인 벡터 색인(V1 {@code vectors}). 벡터는 float64 BLOB이라 저장 정밀도 손실이 없다.
 * 공간·archived·partition·ACL은 SQL WHERE로 거르고, 코사인·top-k는 Java에서 참조 구현과 같은 수식으로 계산한다(동점은 seq 순). 모든
 * SQL이 space_id 조건을 가진다.
 */
public final class SqliteVectorIndex implements VectorIndex {
  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public SqliteVectorIndex(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  /** 후보 행은 질의 수와 관계없이 한 번만 읽는다(seq 순). */
  @Override
  public List<List<ScoredRef>> search(
      SpaceId space, List<double[]> queries, int k, SearchFilter filter) {
    if (queries.isEmpty()) return List.of();
    List<Candidate> candidates = candidates(space, filter);
    List<List<ScoredRef>> out = new ArrayList<>(queries.size());
    for (double[] query : queries) out.add(topK(candidates, query, k));
    return out;
  }

  private List<Candidate> candidates(SpaceId space, SearchFilter filter) {
    boolean restricted = filter.access() instanceof AccessScope.Restricted;
    StatementSpec statement =
        jdbc.sql(
                "SELECT unit_id, document_id, embedding FROM vectors WHERE space_id = :space"
                    + (filter.includeArchived() ? "" : " AND archived = 0")
                    + (filter.partitions() == null
                        ? ""
                        : " AND partition IN (SELECT value FROM json_each(:partitions))")
                    + (restricted ? aclFilter("vectors.document_id") : "")
                    + " ORDER BY seq")
            .param("space", space.value());
    if (filter.partitions() != null)
      statement = statement.param("partitions", json(filter.partitions()));
    return bindKeys(statement, filter.access())
        .query(
            (rs, i) -> {
              double[] vector = VectorCodec.decode(rs.getBytes("embedding"));
              return new Candidate(
                  rs.getString("unit_id"), rs.getString("document_id"), vector, norm(vector));
            })
        .list();
  }

  private static List<ScoredRef> topK(List<Candidate> candidates, double[] query, int k) {
    double normQ = norm(query);
    if (normQ == 0) return List.of();
    List<ScoredRef> hits = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.vector().length != query.length || c.norm() == 0) continue;
      double dot = 0;
      for (int i = 0; i < query.length; i++) dot += query[i] * c.vector()[i];
      double score = dot / (normQ * c.norm());
      if (score > 0) hits.add(new ScoredRef(c.unitId(), c.documentId(), score));
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score())); // 안정 정렬 — 동점은 seq 순
    return List.copyOf(hits.size() > k ? hits.subList(0, k) : hits);
  }

  /** 제곱합을 순서대로 누적한 뒤 sqrt — 참조 구현과 같은 계산. */
  private static double norm(double[] v) {
    double sum = 0;
    for (double x : v) sum += x * x;
    return Math.sqrt(sum);
  }

  /** 호출자의 트랜잭션에 참여하고, 없으면 한 트랜잭션으로 묶는다. 기존 unitId는 seq를 유지한 채 덮어쓴다. */
  @Override
  public void upsert(SpaceId space, List<IndexedUnit> units) {
    if (units.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          for (IndexedUnit u : units)
            jdbc.sql(
                    """
                    INSERT INTO vectors (space_id, unit_id, document_id, partition, archived, embedding)
                    VALUES (:space, :unit, :doc, :partition, :archived, :vec)
                    ON CONFLICT (space_id, unit_id) DO UPDATE SET
                      document_id = excluded.document_id, partition = excluded.partition,
                      archived = excluded.archived, embedding = excluded.embedding
                    """)
                .param("space", space.value())
                .param("unit", u.unitId())
                .param("doc", u.documentId())
                .param("partition", u.partition())
                .param("archived", u.archived() ? 1 : 0)
                .param("vec", VectorCodec.encode(u.embedding()))
                .update();
        });
  }

  @Override
  public void delete(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return;
    tx.executeWithoutResult(
        status ->
            jdbc.sql(
                    "DELETE FROM vectors WHERE space_id = :space AND unit_id IN (SELECT value FROM json_each(:ids))")
                .param("space", space.value())
                .param("ids", json(unitIds))
                .update());
  }

  @Override
  public Map<String, double[]> vectorsOf(SpaceId space, AccessScope access, List<String> unitIds) {
    if (unitIds.isEmpty()) return Map.of();
    StatementSpec statement =
        vectorsQuery(
            space,
            unitIds,
            access instanceof AccessScope.Restricted ? aclFilter("vectors.document_id") : "");
    return readVectors(bindKeys(statement, access), unitIds);
  }

  Map<String, double[]> storedVectors(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return Map.of();
    return readVectors(vectorsQuery(space, unitIds, ""), unitIds);
  }

  private StatementSpec vectorsQuery(SpaceId space, List<String> unitIds, String accessFilter) {
    return jdbc.sql(
            "SELECT unit_id, embedding FROM vectors WHERE space_id = :space"
                + " AND unit_id IN (SELECT value FROM json_each(:ids))"
                + accessFilter)
        .param("space", space.value())
        .param("ids", json(unitIds));
  }

  private Map<String, double[]> readVectors(StatementSpec statement, List<String> unitIds) {
    Map<String, double[]> found = new LinkedHashMap<>();
    statement
        .query((rs, i) -> Map.entry(rs.getString(1), VectorCodec.decode(rs.getBytes(2))))
        .list()
        .forEach(e -> found.put(e.getKey(), e.getValue()));
    Map<String, double[]> out = new LinkedHashMap<>();
    for (String id : unitIds) if (found.containsKey(id)) out.put(id, found.get(id));
    return out;
  }

  /** 조회에서 새로 만든 벡터는 내부에서 읽기만 하며 후보끼리 값 동등성을 비교하지 않는다. */
  @SuppressWarnings("ArrayRecordComponent")
  private record Candidate(String unitId, String documentId, double[] vector, double norm) {}
}
