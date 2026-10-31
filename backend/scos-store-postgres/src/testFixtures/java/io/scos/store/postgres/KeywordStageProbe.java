package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 제품 변경 없이 실제 키워드 단계 문장의 실행 수와 응답 확정 단계를 관찰하는 측정 전용 경계. */
public final class KeywordStageProbe {
  public record Observation(List<String> reached, String settled) {
    public Observation {
      reached = List.copyOf(reached);
    }
  }

  private final JdbcClient jdbc;
  private final PostgresKeywordIndex index;
  private int statements;

  public KeywordStageProbe(DataSource dataSource) {
    jdbc = JdbcClient.create(dataSource);
    JdbcClient observed =
        (JdbcClient)
            Proxy.newProxyInstance(
                JdbcClient.class.getClassLoader(),
                new Class<?>[] {JdbcClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("sql")
                      && args[0].toString().contains("ORDER BY z.score")) statements++;
                  try {
                    return method.invoke(jdbc, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    index = new PostgresKeywordIndex(observed);
  }

  public Observation observe(SpaceId space, String query, int k, SearchFilter filter) {
    boolean rare = hasRarePath(space, query);
    statements = 0;
    String settled = index.answer(space, query, k, filter).level().name();
    if (statements == 0) throw new IllegalStateException("given 준비 실패: 키워드 단계 SQL이 관측되지 않았다");
    List<String> reached = new ArrayList<>();
    // 앞 단계 SQL이 실행됐는지는 실제 문장 수로 센다. 건너뛴 단계는 포함하지 않는다.
    if (statements > 1) reached.add(rare ? "RARE" : "EXACT");
    if (statements > 2) reached.add("EXACT");
    if (statements > 0) reached.add(settled);
    return new Observation(reached, settled);
  }

  private boolean hasRarePath(SpaceId space, String query) {
    List<String> tokens = Text.splitWhitespace(query.toLowerCase(Locale.ROOT));
    Map<String, Integer> frequencies = new LinkedHashMap<>();
    jdbc.sql(
            """
        SELECT g.b AS bigram,
          (SELECT count(*) FROM (SELECT 1 FROM unit_bigrams x
           WHERE x.space_id = ? AND x.bigram = g.b LIMIT ?) y) AS df
        FROM unnest(?::text[]) AS g(b)
        """)
        .params(
            space.value(),
            (long) PostgresKeywordIndex.RARE_LIMIT + 1,
            KeywordPlan.bigramsOf(tokens).toArray(String[]::new))
        .query(
            rs -> {
              frequencies.put(rs.getString("bigram"), rs.getInt("df"));
            });
    return !KeywordPlan.of(tokens, frequencies, PostgresKeywordIndex.RARE_LIMIT)
        .paths(KeywordPlan.Level.RARE)
        .isEmpty();
  }
}
