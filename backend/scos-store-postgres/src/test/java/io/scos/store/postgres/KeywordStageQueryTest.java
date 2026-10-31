package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.KeywordPlan.Level;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 키워드 단계 질의의 SQL 글자와 바인드(골든, 손으로 쓴 기대값). 결과 등가는 PostgresKeywordIndexEquivalenceTest가 본다. DB 없음. */
@DisplayName("키워드 단계 질의는 덮인·덮이지 않은 토큰과 필터마다 정해진 SQL과 바인드를 낸다")
class KeywordStageQueryTest {
  private static final SearchFilter ACTIVE =
      new SearchFilter(false, null, AccessScope.singleUser());
  static final SpaceId SPACE = new SpaceId("s1");
  private static final int RESTRICTED_LIMIT = 5;
  private static final SearchFilter RESTRICTED =
      new SearchFilter(
          false,
          List.of("p1"),
          new AccessScope.Restricted(
              new Principal("alice"), Set.of(AccessKey.parse("user:slack-a:U1"))));
  private static final String RESTRICTED_FILTER =
      " AND u.space_id = ? AND NOT u.archived AND u.partition = ANY(?)"
          + " AND EXISTS (SELECT 1 FROM document_acl a"
          + " WHERE a.space_id = u.space_id AND a.document_id = u.document_id"
          + " AND a.status IN ('ok','unsupported') AND a.keys && ?::text[])";
  private static final String COVERED_AND_OWN_PATHS =
      "WITH r AS MATERIALIZED (SELECT u.seq, 0 AS ord,"
          + " 1::float + (0.5 + 0.5 * (b.cnt::float / (1 + b.cnt)))"
          + " AS s FROM unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq"
          + " WHERE b.space_id = ? AND b.bigram = ? AND u.text LIKE ?"
          + RESTRICTED_FILTER
          + "), o AS MATERIALIZED (SELECT u.seq FROM unit_texts u WHERE u.seq IN ("
          + "SELECT b.unit_seq FROM unit_bigrams b JOIN unit_texts x ON x.seq = b.unit_seq"
          + " WHERE b.space_id = ? AND b.bigram = ? AND x.text LIKE ?)"
          + RESTRICTED_FILTER
          + "), h AS (";
  private static final String MULTIPLE_PATHS =
      " AS s FROM unit_texts u WHERE u.seq IN ("
          + "SELECT b.unit_seq FROM unit_bigrams b JOIN unit_texts x ON x.seq = b.unit_seq"
          + " WHERE b.space_id = ? AND b.bigram = ? AND x.ctext LIKE ? UNION ALL "
          + "SELECT b.unit_seq FROM unit_bigrams b JOIN unit_texts x ON x.seq = b.unit_seq"
          + " WHERE b.space_id = ? AND b.bigram = ? AND x.ctext LIKE ?)"
          + RESTRICTED_FILTER
          + ") h";

  /** "정산"은 드물다(상한 100 이하). */
  static final Map<String, Integer> RARE_DF = Map.of("정산", 10);

  static final String TOTALS_ONE_TOKEN =
      "SELECT u.unit_id, u.document_id, z.score FROM (SELECT h.seq,"
          + " COALESCE(sum(h.s) FILTER (WHERE h.ord = 0), 0) AS score FROM ";
  static final String TOTALS_TAIL =
      " GROUP BY h.seq) z JOIN unit_texts u ON u.seq = z.seq ORDER BY z.score DESC, z.seq LIMIT ?";
  static final String ONE_CHAR_SCORE =
      "(length(u.text) - length(replace(u.text, ?, '')))::float"
          + " / (1 + (length(u.text) - length(replace(u.text, ?, ''))))";
  static final String NO_ROWS =
      "SELECT NULL::bigint AS seq, NULL::int AS ord, NULL::float AS s WHERE false";

  static KeywordStageQuery query(Level level, int k, SearchFilter filter, String... tokens) {
    return new KeywordStageQuery(
        SPACE, KeywordPlan.of(List.of(tokens), RARE_DF, 100), level, k, filter);
  }

  @Test
  @DisplayName("제한 범위는 r과 덮이지 않은 토큰의 o에 접근 필터를 붙인다")
  void restrictsCoveredAndOwnPaths() {
    // when
    var statement = query(Level.RARE, RESTRICTED_LIMIT, RESTRICTED, "정산", "정산일자").statement();

    // then
    assertThat(statement.sql()).startsWith(COVERED_AND_OWN_PATHS);
  }

  @Test
  @DisplayName("r과 o의 바인드는 partition 바로 뒤에 접근 키를 둔다")
  void bindsAccessKeysAfterEachPartition() {
    // when
    var statement = query(Level.RARE, RESTRICTED_LIMIT, RESTRICTED, "정산", "정산일자").statement();

    // then
    assertThat(statement.binds()).containsExactlyElementsOf(coveredAndOwnPathBinds());
  }

  @Test
  @DisplayName("제한 범위는 덮인 토큰의 다중 경로에 접근 필터를 붙인다")
  void restrictsMultiplePaths() {
    // when
    var statement = query(Level.ALL, RESTRICTED_LIMIT, RESTRICTED, "정산일자").statement();

    // then
    assertThat(statement.sql()).contains(MULTIPLE_PATHS);
  }

  @Test
  @DisplayName("다중 경로의 바인드는 partition 바로 뒤에 접근 키를 둔다")
  void bindsMultiplePathAccessKeysAfterPartition() {
    // when
    var statement = query(Level.ALL, RESTRICTED_LIMIT, RESTRICTED, "정산일자").statement();

    // then
    assertThat(statement.binds()).containsExactlyElementsOf(multiplePathBinds());
  }

  /** SQL 자리 순서대로 손으로 쓴 기대값. 배열은 사례마다 새로 만든다. */
  private static List<Object> coveredAndOwnPathBinds() {
    return List.of(
        "s1",
        "정산",
        "%정산%",
        "s1",
        new String[] {"p1"},
        new String[] {"user:slack-a:U1"},
        "s1",
        "정산",
        "%정산일자%",
        "s1",
        new String[] {"p1"},
        new String[] {"user:slack-a:U1"},
        "%정산일자%",
        new String[] {"%정산일%", "%산일자%"},
        "%정산일자%",
        "정산",
        "산일",
        "일자",
        "정산",
        "산일",
        "일자",
        5);
  }

  /** 다중 경로는 점수 식, 경로들, 필터, top-k 순으로 바인드한다. */
  private static List<Object> multiplePathBinds() {
    return List.of(
        "%정산일자%",
        "정산",
        "산일",
        "일자",
        "정산",
        "산일",
        "일자",
        "정산",
        "산일",
        "일자",
        "s1",
        "정산",
        "%정산일%",
        "s1",
        "산일",
        "%산일자%",
        "s1",
        new String[] {"p1"},
        new String[] {"user:slack-a:U1"},
        5);
  }

  @Test
  @DisplayName("덮인 2글자 토큰·필터 없음: 포스팅 경로에서 바로 매기고 보관 유닛을 뺀다")
  void coveredWithoutFilter() {
    // when
    KeywordStageQuery.Statement s = query(Level.RARE, 10, ACTIVE, "정산").statement();

    // then
    assertThat(s.sql())
        .isEqualTo(
            TOTALS_ONE_TOKEN
                + "(SELECT u.seq, 0 AS ord, 1::float + (0.5 + 0.5 * (b.cnt::float / (1 + b.cnt)))"
                + " AS s FROM unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq"
                + " WHERE b.space_id = ? AND b.bigram = ? AND u.text LIKE ?"
                + " AND u.space_id = ? AND NOT u.archived) h"
                + TOTALS_TAIL);
    assertThat(s.binds()).containsExactly("s1", "정산", "%정산%", "s1", 10);
  }

  @Test
  @DisplayName("덮인 1글자 토큰·필터 있음: 전체 LIKE 경로에 partition 조건을 붙이고 보관 유닛도 둔다")
  void coveredWithFilter() {
    // given
    SearchFilter filter = new SearchFilter(true, List.of("p1", "p2"), AccessScope.singleUser());

    // when
    KeywordStageQuery.Statement s = query(Level.EXACT, 3, filter, "가").statement();

    // then
    assertThat(s.sql())
        .isEqualTo(
            TOTALS_ONE_TOKEN
                + "(SELECT u.seq, 0 AS ord, "
                + ONE_CHAR_SCORE
                + " AS s FROM unit_texts u WHERE u.text LIKE ?"
                + " AND u.space_id = ? AND u.partition = ANY(?)) h"
                + TOTALS_TAIL);
    assertThat(s.binds()).containsExactly("가", "가", "%가%", "s1", new String[] {"p1", "p2"}, 3);
  }

  @Test
  @DisplayName("덮이지 않은 토큰·필터 없음: 자기 경로가 없으면 r의 유닛만 후보로 다시 매긴다")
  void uncoveredWithoutFilter() {
    // when
    KeywordStageQuery.Statement s = query(Level.RARE, 10, ACTIVE, "정산", "가").statement();

    // then
    assertThat(s.sql())
        .isEqualTo(
            "WITH r AS MATERIALIZED (SELECT u.seq, 0 AS ord,"
                + " 1::float + (0.5 + 0.5 * (b.cnt::float / (1 + b.cnt)))"
                + " AS s FROM unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq"
                + " WHERE b.space_id = ? AND b.bigram = ? AND u.text LIKE ?"
                + " AND u.space_id = ? AND NOT u.archived)"
                + ", h AS (SELECT seq, ord, s FROM r UNION ALL SELECT u.seq, v.ord, v.s"
                + " FROM (SELECT DISTINCT seq FROM r) c JOIN unit_texts u ON u.seq = c.seq"
                + " CROSS JOIN LATERAL (VALUES (1, CASE WHEN u.text LIKE ? THEN "
                + ONE_CHAR_SCORE
                + " END)) v(ord, s) WHERE v.s IS NOT NULL) "
                + "SELECT u.unit_id, u.document_id, z.score FROM (SELECT h.seq,"
                + " COALESCE(sum(h.s) FILTER (WHERE h.ord = 0), 0)"
                + " + COALESCE(sum(h.s) FILTER (WHERE h.ord = 1), 0) AS score FROM h"
                + TOTALS_TAIL);
    assertThat(s.binds()).containsExactly("s1", "정산", "%정산%", "s1", "%가%", "가", "가", 10);
  }

  @Test
  @DisplayName("덮이지 않은 4글자 토큰·필터 있음: 자기 경로 o에 필터를 걸고 후보를 trigram까지 보고 매긴다")
  void uncoveredWithFilter() {
    // given
    SearchFilter filter = new SearchFilter(false, List.of("p1"), AccessScope.singleUser());
    String count = "(length(u.ctext) - length(replace(u.ctext, ?, ''))) / 2";
    String has = "(strpos(u.ctext, ?) > 0)::int";

    // when
    KeywordStageQuery.Statement s = query(Level.RARE, 5, filter, "정산일자").statement();

    // then
    assertThat(s.sql())
        .isEqualTo(
            "WITH r AS MATERIALIZED ("
                + NO_ROWS
                + "), o AS MATERIALIZED (SELECT u.seq FROM unit_texts u WHERE u.seq IN ("
                + "SELECT b.unit_seq FROM unit_bigrams b JOIN unit_texts x ON x.seq = b.unit_seq"
                + " WHERE b.space_id = ? AND b.bigram = ? AND x.text LIKE ?)"
                + " AND u.space_id = ? AND NOT u.archived AND u.partition = ANY(?))"
                + ", h AS (SELECT seq, ord, s FROM r UNION ALL SELECT u.seq, v.ord, v.s"
                + " FROM (SELECT seq FROM r UNION SELECT seq FROM o) c"
                + " JOIN unit_texts u ON u.seq = c.seq CROSS JOIN LATERAL (VALUES (0, CASE WHEN"
                + " (u.text LIKE ? OR u.ctext LIKE ANY(?)) THEN CASE WHEN u.text LIKE ? THEN"
                + " 1::float + (SELECT 0.5 + 0.5 * (x.m::float / (1 + x.m)) FROM (SELECT LEAST("
                + count
                + ", "
                + count
                + ", "
                + count
                + ") AS m) x) ELSE ("
                + has
                + " + "
                + has
                + " + "
                + has
                + ")::float / 3 END END)) v(ord, s) WHERE v.s IS NOT NULL) "
                + TOTALS_ONE_TOKEN
                + "h"
                + TOTALS_TAIL);
    assertThat(s.binds())
        .containsExactly(
            "s1",
            "정산",
            "%정산일자%",
            "s1",
            new String[] {"p1"},
            "%정산일자%",
            new String[] {"%정산일%", "%산일자%"},
            "%정산일자%",
            "정산",
            "산일",
            "일자",
            "정산",
            "산일",
            "일자",
            5);
  }

  @Test
  @DisplayName("statement()를 두 번 불러도 같은 SQL과 바인드를 낸다")
  void statementIsRepeatable() {
    // given
    KeywordStageQuery query = query(Level.RARE, 10, ACTIVE, "정산", "가");
    KeywordStageQuery.Statement first = query.statement();
    List<Object> firstBinds = List.copyOf(first.binds());

    // when
    KeywordStageQuery.Statement second = query.statement();

    // then
    assertThat(second.sql()).isEqualTo(first.sql());
    assertThat(second.binds()).isEqualTo(firstBinds);
    assertThat(first.binds()).isEqualTo(firstBinds);
  }
}
