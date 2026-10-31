package io.scos.store.postgres;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** U2의 기각된 대안 비용만 잰다. 제품 문장·바인드는 재사용하고 측정 문장에만 탐침을 붙인다. */
public final class VectorCompactionProbe {
  private final JdbcClient jdbc;

  public VectorCompactionProbe(DataSource dataSource) {
    jdbc = JdbcClient.create(dataSource);
  }

  public List<ScoredRef> search(SpaceId space, double[] vector, int k, SearchFilter filter) {
    if (!(filter.access() instanceof AccessScope.Restricted))
      throw new IllegalArgumentException("압축 대안 탐침은 제한 범위에서만 실행한다");
    VectorSearchSql.Statement statement = VectorSearchSql.search(space, vector, k, filter);
    String sql =
        statement
            .sql()
            .replaceFirst(
                "ORDER BY",
                """
        AND NOT EXISTS (SELECT 1 FROM units c
          WHERE c.space_id = v.space_id AND c.id = v.unit_id AND c.tier = 'compacted')
        ORDER BY
        """);
    return jdbc.sql(sql)
        .params(statement.params())
        .query(
            (rs, row) ->
                new ScoredRef(
                    rs.getString("unit_id"), rs.getString("document_id"), rs.getDouble("score")))
        .list();
  }
}
