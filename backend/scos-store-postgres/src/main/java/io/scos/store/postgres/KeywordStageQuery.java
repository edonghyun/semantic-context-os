package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.KeywordPlan.Level;
import io.scos.store.postgres.KeywordPlan.Path;
import io.scos.store.postgres.KeywordPlan.Token;
import io.scos.store.postgres.internal.Bigrams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 키워드 질의 한 단계의 top-k 문장. 토큰마다 (유닛, 토큰 번호, 토큰 점수) 줄을 낸다 — 이 단계의 경로가 토큰의 후보 전체를 덮으면(토큰 상한 0) 그 토큰의
 * 경로가 찾은 유닛에서({@link #coveredTokenRows}), 아니면 이 단계의 후보 c 전체에서 후보 조건을 만족하는 유닛에서({@link
 * #uncoveredTokenRows}). 점수 식: 2글자 이상은 토큰 bigram 커버리지 + 원문 정확 매치 가산 0.5 + 0.5·c/(1+c)(c = 토큰 bigram
 * 최소 등장 횟수), 1글자는 c/(1+c). 커버리지와 등장 횟수는 unit_bigrams가 아니라 그 원본인 공백 제거 본문(ctext)에서 센다({@link
 * #bigramCountSql}) — 2글자 토큰의 경로 줄만 포스팅의 cnt를 쓴다 — 후보마다 bigram 행을 힙에서 읽지 않는다. 유닛별 합은 질의 토큰 순서대로
 * 왼쪽부터 더한다(토큰 번호별 FILTER 합 — 토큰마다 한 줄이라 그 값 자체): 합산 순서가 실행 계획에 달리면 같은 본문의 유닛도 마지막 ULP가 갈린다. 모든 줄의
 * 점수가 양수라(후보 조건을 만족한다) HAVING은 필요 없다.
 *
 * <p>문장 조각을 만드는 메서드는 글을 돌려주고 그 안의 자리표시자 순서대로 바인드를 쌓는다 — 글을 이어 붙이는 순서와 부르는 순서가 같아야 한다.
 */
final class KeywordStageQuery {
  record Statement(String sql, List<Object> binds) {}

  private final SpaceId space;
  private final List<Token> tokens;
  private final Level level;
  private final int k;
  private final SearchFilter filter;
  private final List<Object> binds = new ArrayList<>();
  private final Statement statement;

  KeywordStageQuery(SpaceId space, KeywordPlan plan, Level level, int k, SearchFilter filter) {
    this.space = space;
    this.tokens = plan.tokens();
    this.level = level;
    this.k = k;
    this.filter = filter;
    this.statement = build();
  }

  /** 이 단계의 문장(생성할 때 한 번 만든다 — 바인드가 쌓이는 조각 메서드를 다시 부르지 않는다). */
  Statement statement() {
    return statement;
  }

  private Statement build() {
    List<Token> uncovered = tokens.stream().filter(t -> t.bound(level) > 0).toList();
    StringBuilder sql = new StringBuilder();
    String scored;
    if (uncovered.isEmpty()) {
      // 상한이 남은 토큰이 없으면 줄들을 부질의로 둔다 — 물리화한 CTE 위의 집계는 병렬로 돌지 않는다(토큰 번호별 FILTER 합은 (유닛, 토큰)마다 한
      // 줄이라 병렬 부분 집계로 나눠 더해도 같은 값이다)
      scored = "(" + coveredTokenRows() + ") h";
    } else {
      sql.append("WITH r AS MATERIALIZED (").append(coveredTokenRows()).append(")");
      sql.append(uncoveredTokenRows(uncovered));
      scored = "h";
    }
    sql.append(totals(scored));
    binds.add(k);
    return new Statement(sql.toString(), Collections.unmodifiableList(binds));
  }

  /**
   * 이 단계 경로가 후보 전체를 덮는 토큰의 줄 — 토큰 자기 경로에서 바로 매긴다. 경로가 곧 후보 조건이다(원문 정확 매치, 공백 제거 본문의 trigram, 1글자
   * LIKE). 경로가 하나면 그 조인에서 바로 점수를 매긴다((space_id, bigram, unit_seq)가 유일해 유닛당 한 줄) — 후보를 모은 뒤 본문을 다시
   * 찾지 않는다.
   */
  private String coveredTokenRows() {
    StringBuilder sb = new StringBuilder();
    boolean firstBranch = true;
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.bound(level) > 0) continue;
      if (!firstBranch) sb.append(" UNION ALL ");
      firstBranch = false;
      sb.append("SELECT u.seq, ").append(i).append(" AS ord, ");
      if (token.text().codePointCount(0, token.text().length()) == 2) {
        // 2글자 토큰의 경로 줄은 토큰 bigram 자체의 포스팅이다 — 등장 횟수가 b.cnt, 커버리지 1/1 = 1.0
        sb.append("1::float + (0.5 + 0.5 * (b.cnt::float / (1 + b.cnt)))");
      } else {
        sb.append(tokenScore(token.text(), false));
      }
      sb.append(" AS s FROM ");
      List<Path> paths = token.paths(level);
      if (paths.size() == 1) sb.append(pathRows(paths.get(0))).append(filterSql());
      else sb.append("unit_texts u WHERE ").append(pathsSql(paths));
    }
    if (firstBranch)
      return "SELECT NULL::bigint AS seq, NULL::int AS ord, NULL::float AS s WHERE false";
    return sb.toString();
  }

  /**
   * 이 단계 경로가 후보 전체를 덮지 못하는 토큰: 이 단계 후보 c(= r의 유닛 ∪ 그 토큰들 자기 경로 o)마다 본문을 한 번 읽어 함께 매긴다 — 후보 조건을 만족하는
   * 토큰마다 한 줄. r과 합친 h를 낸다.
   */
  private String uncoveredTokenRows(List<Token> uncovered) {
    StringBuilder sb = new StringBuilder();
    Set<Path> own = new LinkedHashSet<>();
    for (Token t : uncovered) own.addAll(t.paths(level));
    if (!own.isEmpty())
      sb.append(", o AS MATERIALIZED (SELECT u.seq FROM unit_texts u WHERE ")
          .append(pathsSql(List.copyOf(own)))
          .append(")");
    sb.append(", h AS (SELECT seq, ord, s FROM r UNION ALL SELECT u.seq, v.ord, v.s FROM (")
        .append(
            own.isEmpty()
                ? "SELECT DISTINCT seq FROM r"
                : "SELECT seq FROM r UNION SELECT seq FROM o")
        .append(") c JOIN unit_texts u ON u.seq = c.seq CROSS JOIN LATERAL (VALUES ");
    boolean firstValue = true;
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.bound(level) == 0) continue;
      sb.append(firstValue ? "(" : ", (").append(i).append(", CASE WHEN ");
      firstValue = false;
      sb.append(candidateCondition(token.text()))
          .append(" THEN ")
          .append(tokenScore(token.text(), true))
          .append(" END)");
    }
    return sb.append(") v(ord, s) WHERE v.s IS NOT NULL) ").toString();
  }

  /** 유닛별 점수 = 토큰 번호 순 FILTER 합, 점수 내림차순·seq, top-k. */
  private String totals(String scored) {
    StringBuilder sb =
        new StringBuilder("SELECT u.unit_id, u.document_id, z.score FROM (SELECT h.seq, ");
    for (int i = 0; i < tokens.size(); i++)
      sb.append(i > 0 ? " + " : "")
          .append("COALESCE(sum(h.s) FILTER (WHERE h.ord = ")
          .append(i)
          .append("), 0)");
    return sb.append(" AS score FROM ")
        .append(scored)
        .append(
            " GROUP BY h.seq) z JOIN unit_texts u ON u.seq = z.seq ORDER BY z.score DESC, z.seq LIMIT ?")
        .toString();
  }

  /**
   * {@code u.seq IN (경로들) AND 공간·필터}. 경로 = 구동 bigram 포스팅 중 원문(또는 공백 제거 본문) LIKE, 구동 bigram이 없으면 전체
   * LIKE.
   */
  private String pathsSql(List<Path> paths) {
    StringBuilder sb = new StringBuilder("u.seq IN (");
    for (int i = 0; i < paths.size(); i++) {
      Path p = paths.get(i);
      if (i > 0) sb.append(" UNION ALL ");
      if (p.driver() == null) {
        sb.append("SELECT x.seq FROM unit_texts x WHERE x.space_id = ? AND x.text LIKE ?");
        binds.add(space.value());
      } else {
        sb.append(
                "SELECT b.unit_seq FROM unit_bigrams b JOIN unit_texts x ON x.seq = b.unit_seq WHERE b.space_id = ? AND b.bigram = ? AND x.")
            .append(p.compact() ? "ctext" : "text")
            .append(" LIKE ?");
        binds.add(space.value());
        binds.add(p.driver());
      }
      binds.add(p.pattern());
    }
    return sb.append(")").append(filterSql()).toString();
  }

  /** 경로 하나의 유닛을 별칭 u로 내는 FROM·WHERE(이어 붙일 조건은 AND로). */
  private String pathRows(Path p) {
    String sql;
    if (p.driver() == null) {
      sql = "unit_texts u WHERE u.text LIKE ?";
    } else {
      sql =
          "unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq WHERE b.space_id = ? AND b.bigram = ? AND u."
              + (p.compact() ? "ctext" : "text")
              + " LIKE ?";
      binds.add(space.value());
      binds.add(p.driver());
    }
    binds.add(p.pattern());
    return sql;
  }

  /** {@code AND u.space_id = ?}과 archived·partition 필터(top-k 이전). */
  private String filterSql() {
    StringBuilder sb = new StringBuilder(" AND u.space_id = ?");
    binds.add(space.value());
    if (!filter.includeArchived()) sb.append(" AND NOT u.archived");
    if (filter.partitions() != null) {
      sb.append(" AND u.partition = ANY(?)");
      binds.add(filter.partitions().toArray(String[]::new));
    }
    if (filter.access() instanceof AccessScope.Restricted restricted) {
      sb.append(
          " AND EXISTS (SELECT 1 FROM document_acl a"
              + " WHERE a.space_id = u.space_id AND a.document_id = u.document_id"
              + " AND a.status IN ('ok','unsupported') AND a.keys && ?::text[])");
      binds.add(
          restricted.keys().stream().map(AccessKey::toString).sorted().toArray(String[]::new));
    }
    return sb.toString();
  }

  /**
   * 후보 조건: 토큰을 원문에 포함한다. 붙여쓰기 규칙(사용자 결정 2026-10-13): 3글자 이상 토큰은 원문 부분 문자열 **또는** 공백 제거 본문에 토큰
   * trigram 중 하나, 2글자는 원문만(단어 경계 교차 금지).
   */
  private String candidateCondition(String t) {
    binds.add("%" + Bigrams.escapeLike(t) + "%");
    if (t.codePointCount(0, t.length()) < 3) return "u.text LIKE ?";
    binds.add(trigramPatterns(t));
    return "(u.text LIKE ? OR u.ctext LIKE ANY(?))";
  }

  /**
   * 후보 조건을 만족하는 유닛에서 토큰 하나의 점수. {@code countOnce}면 최소 등장 횟수를 스칼라 부질의로 한 번만 센다 — 부질의(SubPlan)는 병렬
   * 실행을 막으므로 병렬로 돌 수 있는 토큰 경로 줄에서는 식을 두 번 쓴다(정확 매치 줄에서만 계산된다).
   */
  private String tokenScore(String t, boolean countOnce) {
    Map<String, Integer> bg = Bigrams.bigramCounts(t);
    if (bg.isEmpty()) {
      // 1코드포인트 토큰: 등장 수 = 길이 차(문자 수) — 나눗셈 불필요(UTF-16 길이로 나누면 아스트랄 문자가 정수 나눗셈 0점)
      binds.add(t);
      binds.add(t);
      return "(length(u.text) - length(replace(u.text, ?, '')))::float / (1 + (length(u.text) - length(replace(u.text, ?, ''))))";
    }
    StringBuilder sb = new StringBuilder();
    // 원문 정확 매치: 토큰 bigram이 전부 있어 커버리지 = |bigram|/|bigram| = 1.0(부동소수로도 정확히)이다 — 커버리지 + (0.5 +
    // 0.5·c/(1+c))와 같은 연산. c = 최소 등장 횟수
    List<String> counts = new ArrayList<>();
    for (String g : bg.keySet()) counts.add(bigramCountSql(g));
    String least = counts.size() == 1 ? counts.get(0) : "LEAST(" + String.join(", ", counts) + ")";
    binds.add("%" + Bigrams.escapeLike(t) + "%");
    sb.append("CASE WHEN u.text LIKE ? THEN 1::float + ");
    if (countOnce) {
      sb.append("(SELECT 0.5 + 0.5 * (x.m::float / (1 + x.m)) FROM (SELECT ")
          .append(least)
          .append(" AS m) x)");
      for (String g : bg.keySet()) binds.add(bigramCountBind(g));
    } else {
      sb.append("(0.5 + 0.5 * ((")
          .append(least)
          .append(")::float / (1 + ")
          .append(least)
          .append(")))");
      for (int twice = 0; twice < 2; twice++)
        for (String g : bg.keySet()) binds.add(bigramCountBind(g));
    }
    // 정확 매치가 아닌 후보 = 공백 제거 매치(3글자 이상): 커버리지 = 본문에 있는 토큰 bigram 수 / |bigram| + 0. 2글자 토큰의 후보는 모두 정확
    // 매치다
    sb.append(" ELSE (");
    boolean first = true;
    for (String g : bg.keySet()) {
      sb.append(first ? "" : " + ").append("(strpos(u.ctext, ?) > 0)::int");
      binds.add(g);
      first = false;
    }
    return sb.append(")::float / ").append(bg.size()).append(" END").toString();
  }

  private static String[] trigramPatterns(String t) {
    return Text.trigrams(t).stream()
        .map(g -> "%" + Bigrams.escapeLike(g) + "%")
        .toArray(String[]::new);
  }

  /**
   * bigram g의 공백 제거 본문 안 등장 횟수(겹침 허용 — {@link Bigrams#bigramCounts}와 같은 셈) SQL 정수식, 바인드 하나({@link
   * #bigramCountBind}). 두 글자가 다르면 g는 자기 자신과 겹칠 수 없어 길이 차 / 2, 같은 글자 둘("aa")이면 겹침을 세려고 전방 탐색 정규식
   * x(?=x).
   */
  private static String bigramCountSql(String g) {
    int[] cps = g.codePoints().toArray();
    return cps[0] != cps[1]
        ? "(length(u.ctext) - length(replace(u.ctext, ?, ''))) / 2"
        : "regexp_count(u.ctext, ?)";
  }

  private static String bigramCountBind(String g) {
    int[] cps = g.codePoints().toArray();
    if (cps[0] != cps[1]) return g;
    String x = Bigrams.regexLiteral(cps[0]);
    return x + "(?=" + x + ")";
  }
}
