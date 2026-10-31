package io.scos.store.sqlite;

import io.scos.spi.store.EmbeddingCache;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** SQLite 임베딩 캐시(V1 {@code embedding_cache}). 벡터는 float64 BLOB(리틀 엔디언)이라 저장 정밀도 손실이 없다. */
public final class SqliteEmbeddingCache implements EmbeddingCache {
  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public SqliteEmbeddingCache(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public Map<String, double[]> find(String generation, Collection<String> inputKeys) {
    Map<String, double[]> out = new LinkedHashMap<>();
    if (inputKeys.isEmpty()) return out;
    jdbc.sql(
            "SELECT input_sha256, embedding FROM embedding_cache WHERE generation = :g"
                + " AND input_sha256 IN (SELECT value FROM json_each(:keys))")
        .param("g", generation)
        .param("keys", SqlParts.json(inputKeys))
        .query((rs, i) -> Map.entry(rs.getString(1), VectorCodec.decode(rs.getBytes(2))))
        .list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  /** 호출자의 트랜잭션에 참여하고, 없으면 한 트랜잭션으로 묶어 넣는다. */
  @Override
  public int putIfAbsent(String generation, Map<String, double[]> entries) {
    if (entries.isEmpty()) return 0;
    Integer inserted =
        tx.execute(
            status -> {
              int n = 0;
              for (Map.Entry<String, double[]> e : entries.entrySet())
                n +=
                    jdbc.sql(
                            "INSERT INTO embedding_cache (generation, input_sha256, embedding) VALUES (:g, :key, :vec) ON CONFLICT (generation, input_sha256) DO NOTHING")
                        .param("g", generation)
                        .param("key", e.getKey())
                        .param("vec", VectorCodec.encode(e.getValue()))
                        .update();
              return n;
            });
    return inserted == null ? 0 : inserted;
  }

  @Override
  public int size() {
    return jdbc.sql("SELECT count(*) FROM embedding_cache").query(Integer.class).single();
  }

  @Override
  public int clear() {
    return jdbc.sql("DELETE FROM embedding_cache").update();
  }
}
