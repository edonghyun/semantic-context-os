package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.KeywordPlan.Level;
import io.scos.store.postgres.internal.Bigrams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 직접 bigram 테이블 어댑터(ADR-0014 P-4 선택). 질의 토큰(공백 분리)마다:
 *
 * <ul>
 *   <li>2글자 이상: bigram으로 후보를 모으되 토큰을 실제로 포함하는 유닛만 — 2글자는 원문 부분 문자열, 3글자 이상은 원문 또는 공백 제거 본문의 토큰
 *       trigram 하나(TS compact trigram-OR 등가, 리뷰 I-5·N-1), 점수 = 토큰 bigram 커버리지(0..1) + 정확 부분 문자열이면
 *       0.5 + 0.5·c/(1+c)(c = 토큰 bigram 최소 등장 횟수 — T12.1 정확 > 공백 소실)
 *   <li>1글자: LIKE 전체 스캔, 등장 횟수 c/(1+c)
 * </ul>
 *
 * 유닛별 합산, 점수 내림차순, 동점은 seq. 필터는 WHERE(= top-k 이전). 같은 DataSource를 쓰면 투영 트랜잭션에 참여한다(TRANSACTIONAL).
 *
 * <p>후보는 토큰(또는 trigram)의 가장 드문 bigram 하나로 찾고, 흔한 bigram만 가진 토큰은 점수 상한으로 앞 단계에서 거른다({@link
 * KeywordPlan}, ADR-0014 P-4 부록 "드문 bigram 구동") — 결과는 bigram 전부로 후보를 모으던 형태와 같다.
 */
public final class PostgresKeywordIndex implements KeywordIndex {
  /** 문장 하나에 묶는 유닛 수 — 배열 바인드 크기(유닛당 bigram 수십 개)를 묶음마다 제한한다. */
  static final int UNITS_PER_STATEMENT = 1_000;

  /**
   * 드묾 상한: 문서 빈도가 이 이하인 bigram만 RARE 단계 후보 경로를 구동한다. RARE 단계 비용(포스팅 행 × LIKE)의 토큰 경로당 상한이자 탐침이
   * bigram마다 세는 최대 행 수다. 운영 20.8만 유닛의 약 5%(ADR-0014 P-4 부록).
   */
  static final int RARE_LIMIT = 10_000;

  /**
   * EXACT 단계를 시도할 만큼 흔한 문서 빈도: 빼놓을 trigram 경로의 구동 bigram 중 하나라도 이보다 흔해야 한다. 운영 20.8만 유닛의 약 24% —
   * 운영의 숫자 bigram "20"은 15만 유닛(72%)에 있다.
   */
  static final int HUGE_LIMIT = 50_000;

  private final JdbcClient jdbc;
  private final int rareLimit;
  private final int hugeLimit;

  public PostgresKeywordIndex(JdbcClient jdbc) {
    this(jdbc, RARE_LIMIT, HUGE_LIMIT);
  }

  /** 테스트가 상한을 바꿔 단계가 모두 돌게 한다. */
  PostgresKeywordIndex(JdbcClient jdbc, int rareLimit, int hugeLimit) {
    this.jdbc = jdbc;
    this.rareLimit = rareLimit;
    this.hugeLimit = hugeLimit;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
    return answer(space, text, k, filter).refs();
  }

  /** 검색 결과, 답이 나온 단계와 그 단계의 상한(등가 테스트가 단계·상한 확정이 모두 돌았는지 본다). */
  record Answer(List<ScoredRef> refs, Level level, int bound) {}

  /**
   * {@link KeywordPlan}의 단계별 검색: 문서 빈도 탐침(bigram마다 상한 + 1개까지만 센다) → RARE(드문 경로) → EXACT(정확 매치 경로
   * 전부) → ALL. 앞 단계의 top-k가 그 단계 상한을 넘으면 거기서 끝난다. 경로가 앞 단계와 같거나(다시 볼 것이 없다) 상한이 0인(ALL과 같다) 단계는
   * 건너뛴다. 각 단계가 한 문장이라 답은 한 스냅샷의 것이다(탐침 뒤의 쓰기는 경로 선택만 바꾸고 답은 바꾸지 않는다).
   */
  Answer answer(SpaceId space, String text, int k, SearchFilter filter) {
    List<String> tokens = Text.splitWhitespace(text.toLowerCase(Locale.ROOT));
    if (tokens.isEmpty()) return new Answer(List.of(), Level.RARE, 0);
    KeywordPlan plan =
        KeywordPlan.of(
            tokens,
            documentFrequencies(space, KeywordPlan.bigramsOf(tokens), rareLimit),
            rareLimit);
    if (!plan.paths(Level.RARE).isEmpty()) {
      List<ScoredRef> top = query(space, plan, Level.RARE, k, filter);
      if (plan.settles(Level.RARE, top, k))
        return new Answer(top, Level.RARE, plan.bound(Level.RARE));
    }
    // EXACT는 흔한 trigram 경로를 빼놓아 아낄 때만 시도한다 — 빼놓은 구동 bigram이 hugeLimit를 넘지 않으면 ALL과 비용이 비슷하고 한 번 더
    // 도는 셈이다
    if (plan.bound(Level.EXACT) > 0
        && !plan.paths(Level.EXACT).equals(plan.paths(Level.RARE))
        && documentFrequencies(space, plan.omittedDrivers(Level.EXACT), hugeLimit).values().stream()
            .anyMatch(n -> n > hugeLimit)) {
      List<ScoredRef> top = query(space, plan, Level.EXACT, k, filter);
      if (plan.settles(Level.EXACT, top, k))
        return new Answer(top, Level.EXACT, plan.bound(Level.EXACT));
    }
    return new Answer(query(space, plan, Level.ALL, k, filter), Level.ALL, 0);
  }

  /** bigram → 문서 빈도(cap + 1에서 자른다 — 흔한 bigram의 포스팅을 끝까지 세지 않는다). 색인 전용 스캔. */
  private Map<String, Integer> documentFrequencies(SpaceId space, List<String> bigrams, int cap) {
    Map<String, Integer> df = new LinkedHashMap<>();
    if (bigrams.isEmpty()) return df;
    jdbc.sql(
            """
            SELECT g.b AS bigram,
                   (SELECT count(*) FROM (SELECT 1 FROM unit_bigrams x WHERE x.space_id = ? AND x.bigram = g.b LIMIT ?) y) AS df
            FROM unnest(?::text[]) AS g(b)
            """)
        .params(space.value(), (long) cap + 1, bigrams.toArray(String[]::new))
        .query(
            rs -> {
              df.put(rs.getString("bigram"), rs.getInt("df"));
            });
    return df;
  }

  /**
   * 한 단계의 top-k. 토큰마다 (유닛, 토큰 번호, 토큰 점수) 줄을 낸다 — 이 단계의 경로가 토큰의 후보 전체를 덮으면(토큰 상한 0) 그 토큰의 경로가 찾은
   * 유닛에서, 아니면 이 단계의 후보 c 전체에서 후보 조건을 만족하는 유닛에서. 점수 식은 옛 SQL과 같다: 2글자 이상은 토큰 bigram 커버리지 + 원문 정확 매치
   * 가산 0.5 + 0.5·c/(1+c)(c = 토큰 bigram 최소 등장 횟수), 1글자는 c/(1+c). 커버리지와 등장 횟수는 unit_bigrams가 아니라 그
   * 원본인 공백 제거 본문(ctext)에서 센다({@link #bigramCountSql}) — 2글자 토큰의 경로 줄만 포스팅의 cnt를 쓴다 — 후보마다 bigram 행을
   * 힙에서 읽지 않는다. 유닛별 합은 질의 토큰 순서대로 왼쪽부터 더한다(토큰 번호별 FILTER 합 — 토큰마다 한 줄이라 그 값 자체). 옛 SUM(UNION ALL)은
   * 합산 순서가 실행 계획(정렬 그룹·병렬 집계)에 달려 같은 본문의 유닛도 마지막 ULP가 갈렸다. 모든 줄의 점수가 양수라(후보 조건을 만족한다) 옛 HAVING SUM
   * &gt; 0은 필요 없다.
   */
  private List<ScoredRef> query(
      SpaceId space, KeywordPlan plan, Level level, int k, SearchFilter filter) {
    List<KeywordPlan.Token> tokens = plan.tokens();
    List<Object> binds = new ArrayList<>();
    // r: 이 단계 경로가 후보 전체를 덮는 토큰의 (유닛, 토큰 번호, 점수) — 토큰 자기 경로에서 바로 매긴다
    List<KeywordPlan.Token> partial = tokens.stream().filter(t -> t.bound(level) > 0).toList();
    // 상한이 남은 토큰이 없으면 r을 부질의로 둔다 — 물리화한 CTE 위의 집계는 병렬로 돌지 않는다(토큰 번호별 FILTER 합은 (유닛, 토큰)마다 한 줄이라 병렬
    // 부분
    // 집계로 나눠 더해도 같은 값이다)
    StringBuilder sb = new StringBuilder(partial.isEmpty() ? "" : "WITH r AS MATERIALIZED (");
    boolean firstBranch = true;
    for (int i = 0; i < tokens.size(); i++) {
      KeywordPlan.Token token = tokens.get(i);
      if (token.bound(level) > 0) continue;
      if (!firstBranch) sb.append(" UNION ALL ");
      firstBranch = false;
      // 경로가 곧 후보 조건이다(원문 정확 매치, 공백 제거 본문의 trigram, 1글자 LIKE). 경로가 하나면 그 조인에서 바로 점수를 매긴다
      // ((space_id, bigram, unit_seq)가 유일해 유닛당 한 줄) — 후보를 모은 뒤 본문을 다시 찾지 않는다
      sb.append("SELECT u.seq, ").append(i).append(" AS ord, ");
      List<KeywordPlan.Path> paths = token.paths(level);
      if (token.text().codePointCount(0, token.text().length()) == 2) {
        // 2글자 토큰의 경로 줄은 토큰 bigram 자체의 포스팅이다 — 등장 횟수가 b.cnt(옛 min(b.cnt)), 커버리지 1/1 = 1.0
        sb.append("1::float + (0.5 + 0.5 * (b.cnt::float / (1 + b.cnt)))");
      } else {
        appendTokenScore(sb, binds, token.text(), false);
      }
      sb.append(" AS s FROM ");
      if (paths.size() == 1) {
        appendPathRows(sb, binds, space, paths.get(0));
        appendFilter(sb, binds, space, filter);
      } else {
        sb.append("unit_texts u WHERE ");
        appendPaths(sb, binds, space, paths, filter);
      }
    }
    if (firstBranch)
      sb.append("SELECT NULL::bigint AS seq, NULL::int AS ord, NULL::float AS s WHERE false");
    String hSource;
    if (partial.isEmpty()) {
      hSource = "(" + sb + ") h";
      sb.setLength(0);
    } else {
      sb.append(")");
      hSource = "h";
      // 이 단계 경로가 후보 전체를 덮지 못하는 토큰: 이 단계 후보 c(= r의 유닛 ∪ 그 토큰들 자기 경로 o)마다 본문을 한 번 읽어 함께 매긴다 — 후보
      // 조건을 만족하는 토큰마다 한 줄
      Set<KeywordPlan.Path> own = new LinkedHashSet<>();
      for (KeywordPlan.Token t : partial) own.addAll(t.paths(level));
      if (!own.isEmpty()) {
        sb.append(", o AS MATERIALIZED (SELECT u.seq FROM unit_texts u WHERE ");
        appendPaths(sb, binds, space, List.copyOf(own), filter);
        sb.append(")");
      }
      sb.append(", h AS (SELECT seq, ord, s FROM r UNION ALL SELECT u.seq, v.ord, v.s FROM (")
          .append(
              own.isEmpty()
                  ? "SELECT DISTINCT seq FROM r"
                  : "SELECT seq FROM r UNION SELECT seq FROM o")
          .append(") c JOIN unit_texts u ON u.seq = c.seq CROSS JOIN LATERAL (VALUES ");
      boolean firstValue = true;
      for (int i = 0; i < tokens.size(); i++) {
        KeywordPlan.Token token = tokens.get(i);
        if (token.bound(level) == 0) continue;
        sb.append(firstValue ? "(" : ", (").append(i).append(", CASE WHEN ");
        firstValue = false;
        appendCandidateCondition(sb, binds, token.text());
        sb.append(" THEN ");
        appendTokenScore(sb, binds, token.text(), true);
        sb.append(" END)");
      }
      sb.append(") v(ord, s) WHERE v.s IS NOT NULL) ");
    }
    sb.append("SELECT u.unit_id, u.document_id, z.score FROM (SELECT h.seq, ");
    for (int i = 0; i < tokens.size(); i++)
      sb.append(i > 0 ? " + " : "")
          .append("COALESCE(sum(h.s) FILTER (WHERE h.ord = ")
          .append(i)
          .append("), 0)");
    sb.append(
        " AS score FROM "
            + hSource
            + " GROUP BY h.seq) z JOIN unit_texts u ON u.seq = z.seq ORDER BY z.score DESC, z.seq LIMIT ?");
    binds.add(k);
    return jdbc.sql(sb.toString())
        .params(binds)
        .query(
            (rs, i) ->
                new ScoredRef(
                    rs.getString("unit_id"), rs.getString("document_id"), rs.getDouble("score")))
        .list();
  }

  /**
   * {@code u.seq IN (경로들) AND 공간·필터}. 경로 = 구동 bigram 포스팅 중 원문(또는 공백 제거 본문) LIKE, 구동 bigram이 없으면 전체
   * LIKE.
   */
  private static void appendPaths(
      StringBuilder sb,
      List<Object> binds,
      SpaceId space,
      List<KeywordPlan.Path> paths,
      SearchFilter filter) {
    sb.append("u.seq IN (");
    for (int i = 0; i < paths.size(); i++) {
      KeywordPlan.Path p = paths.get(i);
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
    sb.append(")");
    appendFilter(sb, binds, space, filter);
  }

  /** 경로 하나의 유닛을 별칭 u로 내는 FROM·WHERE(이어 붙일 조건은 AND로). */
  private static void appendPathRows(
      StringBuilder sb, List<Object> binds, SpaceId space, KeywordPlan.Path p) {
    if (p.driver() == null) {
      sb.append("unit_texts u WHERE u.text LIKE ?");
    } else {
      sb.append(
              "unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq WHERE b.space_id = ? AND b.bigram = ? AND u.")
          .append(p.compact() ? "ctext" : "text")
          .append(" LIKE ?");
      binds.add(space.value());
      binds.add(p.driver());
    }
    binds.add(p.pattern());
  }

  /** {@code AND u.space_id = ?}과 archived·partition 필터(top-k 이전). */
  private static void appendFilter(
      StringBuilder sb, List<Object> binds, SpaceId space, SearchFilter filter) {
    sb.append(" AND u.space_id = ?");
    binds.add(space.value());
    if (!filter.includeArchived()) sb.append(" AND NOT u.archived");
    if (filter.partitions() != null) {
      sb.append(" AND u.partition = ANY(?)");
      binds.add(filter.partitions().toArray(String[]::new));
    }
  }

  /**
   * 후보 조건(리뷰 I-5): 토큰을 원문에 포함한다. 붙여쓰기 규칙 = TS B3 compact 경로 등가(사용자 결정 2026-10-13): 3글자 이상 토큰은 원문 부분
   * 문자열 **또는** 공백 제거 본문에 토큰 trigram 중 하나, 2글자는 원문만(단어 경계 교차 금지).
   */
  private static void appendCandidateCondition(StringBuilder sb, List<Object> binds, String t) {
    binds.add("%" + Bigrams.escapeLike(t) + "%");
    if (t.codePointCount(0, t.length()) < 3) {
      sb.append("u.text LIKE ?");
      return;
    }
    sb.append("(u.text LIKE ? OR u.ctext LIKE ANY(?))");
    binds.add(trigramPatterns(t));
  }

  /**
   * 후보 조건을 만족하는 유닛에서 토큰 하나의 점수. {@code countOnce}면 최소 등장 횟수를 스칼라 부질의로 한 번만 센다 — 부질의(SubPlan)는 병렬
   * 실행을 막으므로 병렬로 돌 수 있는 토큰 경로 줄에서는 식을 두 번 쓴다(정확 매치 줄에서만 계산된다).
   */
  private static void appendTokenScore(
      StringBuilder sb, List<Object> binds, String t, boolean countOnce) {
    Map<String, Integer> bg = Bigrams.bigramCounts(t);
    if (bg.isEmpty()) {
      // 1코드포인트 토큰: 등장 수 = 길이 차(문자 수) — 나눗셈 불필요(리뷰 M-3: UTF-16 길이로 나누면 아스트랄 문자가 정수 나눗셈 0점)
      sb.append(
          "(length(u.text) - length(replace(u.text, ?, '')))::float / (1 + (length(u.text) - length(replace(u.text, ?, ''))))");
      binds.add(t);
      binds.add(t);
      return;
    }
    // 원문 정확 매치: 토큰 bigram이 전부 있어 커버리지 = |bigram|/|bigram| = 1.0(부동소수로도 정확히)이다 — 옛 식 커버리지 + (0.5 +
    // 0.5·c/(1+c))와 같은 연산. c = 최소 등장 횟수(옛 min(b.cnt))
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
    // 정확 매치가 아닌 후보 = 공백 제거 매치(3글자 이상): 커버리지 = 본문에 있는 토큰 bigram 수(옛 count(DISTINCT b.bigram)와 같은 정수)
    // / |bigram| + 0. 2글자 토큰의 후보는 모두 정확 매치다
    sb.append(" ELSE (");
    boolean first = true;
    for (String g : bg.keySet()) {
      sb.append(first ? "" : " + ").append("(strpos(u.ctext, ?) > 0)::int");
      binds.add(g);
      first = false;
    }
    sb.append(")::float / ").append(bg.size()).append(" END");
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

  /**
   * M-11 배치 적재: 묶음({@link #UNITS_PER_STATEMENT})마다 문장 셋 — unit_texts upsert(unnest, 입력 순서 = seq), 그
   * 유닛들의 bigram 삭제, 펼친 bigram 삽입(unit_id로 seq 조인). 같은 unitId가 다시 나오면 마지막 본문을 처음 자리에 쓴다(행 단위 upsert와
   * 같은 결과 — 한 문장의 ON CONFLICT는 같은 행을 두 번 갱신하지 못한다). 트랜잭션은 호출자 몫이다.
   */
  @Override
  public void upsert(SpaceId space, List<IndexedText> units) {
    Map<String, IndexedText> lastByFirstPosition = new LinkedHashMap<>();
    for (IndexedText u : units) lastByFirstPosition.put(u.unitId(), u);
    List<IndexedText> distinct = List.copyOf(lastByFirstPosition.values());
    for (int from = 0; from < distinct.size(); from += UNITS_PER_STATEMENT)
      upsertChunk(
          space, distinct.subList(from, Math.min(from + UNITS_PER_STATEMENT, distinct.size())));
  }

  private void upsertChunk(SpaceId space, List<IndexedText> chunk) {
    int n = chunk.size();
    String[] unitIds = new String[n];
    String[] documentIds = new String[n];
    String[] partitions = new String[n];
    Boolean[] archived = new Boolean[n];
    String[] texts = new String[n];
    String[] compactTexts = new String[n];
    List<String> bigramUnits = new ArrayList<>();
    List<String> bigrams = new ArrayList<>();
    List<Integer> counts = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      IndexedText u = chunk.get(i);
      String text = Bigrams.normalize(u);
      unitIds[i] = u.unitId();
      documentIds[i] = u.documentId();
      partitions[i] = u.partition();
      archived[i] = u.archived();
      texts[i] = text;
      compactTexts[i] = Text.stripWhitespace(text);
      Bigrams.bigramCounts(text)
          .forEach(
              (bigram, count) -> {
                bigramUnits.add(u.unitId());
                bigrams.add(bigram);
                counts.add(count);
              });
    }
    jdbc.sql(
            """
            INSERT INTO unit_texts (space_id, unit_id, document_id, partition, archived, text, ctext)
            SELECT :space, t.unit_id, t.document_id, t.partition, t.archived, t.text, t.ctext
            FROM unnest(:units, :docs, :partitions, :archived, :texts, :ctexts)
                 WITH ORDINALITY AS t(unit_id, document_id, partition, archived, text, ctext, ord)
            ORDER BY t.ord
            ON CONFLICT (space_id, unit_id) DO UPDATE
              SET document_id = EXCLUDED.document_id, partition = EXCLUDED.partition, archived = EXCLUDED.archived, text = EXCLUDED.text, ctext = EXCLUDED.ctext
            """)
        .param("space", space.value())
        .param("units", unitIds)
        .param("docs", documentIds)
        .param("partitions", partitions)
        .param("archived", archived)
        .param("texts", texts)
        .param("ctexts", compactTexts)
        .update();
    // seq 배열을 InitPlan으로 먼저 만들어 unit_bigrams_unit 색인 조회로 고정한다. 측정한 사실: IN (SELECT …) 형태에서는
    // SearchScaleProbeTest 5,000유닛 적재 중 시트 문서(300유닛) 하나의 삭제가 305ms → 967ms로 표와 함께 늘었고, 이 형태로는
    // 4–8ms로 일정했다. 그때의 실행 계획은 남기지 않았다 — 별도 EXPLAIN 재현(20만 × 30 bigram)에서는 옛 형태도 색인을 썼다(F3 리뷰 m2).
    jdbc.sql(
            """
            DELETE FROM unit_bigrams WHERE space_id = :space
              AND unit_seq = ANY(ARRAY(SELECT seq FROM unit_texts WHERE space_id = :space AND unit_id = ANY(:units)))
            """)
        .param("space", space.value())
        .param("units", unitIds)
        .update();
    if (bigrams.isEmpty()) return;
    jdbc.sql(
            """
            INSERT INTO unit_bigrams (space_id, unit_seq, bigram, cnt)
            SELECT :space, u.seq, t.b, t.c
            FROM unnest(:bigramUnits, :bigrams, :counts) AS t(unit_id, b, c)
            JOIN unit_texts u ON u.space_id = :space AND u.unit_id = t.unit_id
            """)
        .param("space", space.value())
        .param("bigramUnits", bigramUnits.toArray(String[]::new))
        .param("bigrams", bigrams.toArray(String[]::new))
        .param("counts", counts.toArray(Integer[]::new))
        .update();
  }

  @Override
  public void delete(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return;
    jdbc.sql("DELETE FROM unit_texts WHERE space_id = :space AND unit_id = ANY(:units)")
        .param("space", space.value())
        .param("units", unitIds.toArray(String[]::new))
        .update();
  }
}
