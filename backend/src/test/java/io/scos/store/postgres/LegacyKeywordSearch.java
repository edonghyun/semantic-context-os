package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.internal.Bigrams;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 등가 오라클: 드문 bigram 구동(ADR-0014 P-4 부록) 이전의 {@link PostgresKeywordIndex#search} SQL을 3d2afc0c 그대로
 * 옮겨 둔 것. 토큰의 bigram 전부로 후보를 모은다 — 흔한 bigram 하나가 후보를 폭발시키는 원래 형태라 등가 테스트의 기대값과 성능 프로브의 "전" 측정에 쓴다.
 * 후보 조건·토큰 점수 식은 고치지 않는다(점수·순서 규칙의 기준, 합산 순서만 생성자 인자).
 */
final class LegacyKeywordSearch {
  private final JdbcClient jdbc;
  private final boolean tokenOrderSum;

  /**
   * @param tokenOrderSum 참이면 유닛별 합을 질의 토큰 순서로 더한다({@code SUM(h.s ORDER BY 토큰 번호)}) — 원래 SQL의 {@code
   *     SUM}은 합산 순서가 실행 계획(정렬 그룹·병렬 집계)에 달려 같은 본문의 두 유닛도 마지막 ULP가 갈린다(등가 테스트가 처음 잡은 사실). 등가 기대값은 이
   *     판을, 성능 "전" 측정은 원래 판(거짓)을 쓴다. 후보·토큰 점수 식은 두 판이 같다
   */
  LegacyKeywordSearch(JdbcClient jdbc, boolean tokenOrderSum) {
    this.jdbc = jdbc;
    this.tokenOrderSum = tokenOrderSum;
  }

  List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
    String[] tokens = Text.splitWhitespace(text.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    if (tokens.length == 0) return List.of();
    StringBuilder sb =
        new StringBuilder(
            "SELECT u.unit_id, u.document_id, SUM(h.s"
                + (tokenOrderSum ? " ORDER BY h.ord" : "")
                + ") AS score FROM (");
    List<Object> binds = new ArrayList<>();
    for (int i = 0; i < tokens.length; i++) {
      String t = tokens[i];
      Map<String, Integer> bg = Bigrams.bigramCounts(t);
      if (i > 0) sb.append(" UNION ALL ");
      if (bg.isEmpty()) {
        sb.append(
                "SELECT "
                    + i
                    + " AS ord, seq, (length(text) - length(replace(text, ?, '')))::float / (1 + (length(text) - length(replace(text, ?, ''))))")
            .append(" AS s FROM unit_texts WHERE space_id = ? AND text LIKE ?");
        binds.add(t);
        binds.add(t);
        binds.add(space.value());
        binds.add("%" + Bigrams.escapeLike(t) + "%");
      } else {
        boolean compactAllowed = t.codePointCount(0, t.length()) >= 3;
        String pat = "%" + Bigrams.escapeLike(t) + "%";
        String[] trigramPatterns =
            Text.trigrams(t).stream()
                .map(g -> "%" + Bigrams.escapeLike(g) + "%")
                .toArray(String[]::new);
        sb.append("SELECT " + i + " AS ord, b.unit_seq AS seq, count(DISTINCT b.bigram)::float / ")
            .append(bg.size())
            .append(
                " + CASE WHEN u.text LIKE ? THEN 0.5 + 0.5 * (min(b.cnt)::float / (1 + min(b.cnt))) ELSE 0 END AS s")
            .append(
                " FROM unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq WHERE b.space_id = ? AND b.bigram = ANY(?)")
            .append(
                compactAllowed
                    ? " AND (u.text LIKE ? OR u.ctext LIKE ANY(?))"
                    : " AND u.text LIKE ?")
            .append(" GROUP BY b.unit_seq, u.text");
        binds.add(pat);
        binds.add(space.value());
        binds.add(bg.keySet().toArray(String[]::new));
        binds.add(pat);
        if (compactAllowed) binds.add(trigramPatterns);
      }
    }
    sb.append(") h JOIN unit_texts u ON u.seq = h.seq WHERE u.space_id = ?");
    binds.add(space.value());
    if (!filter.includeArchived()) sb.append(" AND NOT u.archived");
    if (filter.partitions() != null) {
      sb.append(" AND u.partition = ANY(?)");
      binds.add(filter.partitions().toArray(String[]::new));
    }
    sb.append(
        " GROUP BY u.seq, u.unit_id, u.document_id HAVING SUM(h.s) > 0 ORDER BY score DESC, u.seq LIMIT ?");
    binds.add(k);
    return jdbc.sql(sb.toString())
        .params(binds)
        .query(
            (rs, i) ->
                new ScoredRef(
                    rs.getString("unit_id"), rs.getString("document_id"), rs.getDouble("score")))
        .list();
  }
}
