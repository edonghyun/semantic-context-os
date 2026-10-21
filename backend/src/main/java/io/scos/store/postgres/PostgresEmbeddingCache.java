package io.scos.store.postgres;

import io.scos.spi.store.EmbeddingCache;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 임베딩 캐시의 PostgreSQL 어댑터(V16 `embedding_cache`). 벡터는 pgvector(float4) — `unit_vectors`와 같은 저장 정밀도다.
 */
public final class PostgresEmbeddingCache implements EmbeddingCache {
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  public PostgresEmbeddingCache(NamedParameterJdbcTemplate batch) {
    this.batch = batch;
    this.jdbc = JdbcClient.create(batch.getJdbcTemplate());
  }

  @Override
  public Map<String, double[]> find(String generation, Collection<String> inputKeys) {
    Map<String, double[]> out = new LinkedHashMap<>();
    if (inputKeys.isEmpty()) return out;
    jdbc.sql(
            "SELECT input_sha256, embedding::text AS v FROM embedding_cache WHERE generation = :g AND input_sha256 = ANY(:keys)")
        .param("g", generation)
        .param("keys", inputKeys.toArray(String[]::new))
        .query(
            (rs, i) ->
                Map.entry(
                    rs.getString("input_sha256"), PostgresVectorIndex.parse(rs.getString("v"))))
        .list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  @Override
  public int putIfAbsent(String generation, Map<String, double[]> entries) {
    if (entries.isEmpty()) return 0;
    int[] counts =
        batch.batchUpdate(
            """
            INSERT INTO embedding_cache (generation, input_sha256, embedding)
            VALUES (:g, :key, CAST(:vec AS vector)) ON CONFLICT (generation, input_sha256) DO NOTHING
            """,
            entries.entrySet().stream()
                .map(
                    e ->
                        new MapSqlParameterSource()
                            .addValue("g", generation)
                            .addValue("key", e.getKey())
                            .addValue("vec", PostgresVectorIndex.literal(e.getValue())))
                .toArray(SqlParameterSource[]::new));
    int inserted = 0;
    for (int c : counts) inserted += Math.max(c, 0);
    return inserted;
  }

  @Override
  public int size() {
    return jdbc.sql("SELECT count(*) FROM embedding_cache").query(Integer.class).single();
  }

  /**
   * TRUNCATE로 비운다 — DELETE와 달리 디스크 공간을 VACUUM 없이 바로 돌려준다(20만 행에 약 856MB, recollection.md). 표와 색인은
   * 남는다. ACCESS EXCLUSIVE 잠금을 잡으므로 재수집이 끝난 뒤에 쓴다.
   */
  @Override
  public int clear() {
    int keys = size();
    jdbc.sql("TRUNCATE embedding_cache").update();
    return keys;
  }
}
