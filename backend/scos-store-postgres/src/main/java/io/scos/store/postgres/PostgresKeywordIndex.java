package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.KeywordPlan.Level;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 직접 bigram 테이블 어댑터(ADR-0014 P-4 선택). 질의 토큰(공백 분리)마다:
 *
 * <ul>
 *   <li>2글자 이상: bigram으로 후보를 모으되 토큰을 실제로 포함하는 유닛만 — 2글자는 원문 부분 문자열, 3글자 이상은 원문 또는 공백 제거 본문의 토큰
 *       trigram 하나(compact trigram-OR), 점수 = 토큰 bigram 커버리지(0..1) + 정확 부분 문자열이면 0.5 + 0.5·c/(1+c)(c
 *       = 토큰 bigram 최소 등장 횟수 — 정확 매치가 공백 소실 매치보다 앞선다)
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

  /** 한 단계의 top-k({@link KeywordStageQuery}). */
  private List<ScoredRef> query(
      SpaceId space, KeywordPlan plan, Level level, int k, SearchFilter filter) {
    KeywordStageQuery.Statement statement =
        new KeywordStageQuery(space, plan, level, k, filter).statement();
    return jdbc.sql(statement.sql())
        .params(statement.binds())
        .query(
            (rs, i) ->
                new ScoredRef(
                    rs.getString("unit_id"), rs.getString("document_id"), rs.getDouble("score")))
        .list();
  }

  /**
   * 배치 적재: 묶음({@link #UNITS_PER_STATEMENT})마다 문장 셋 — unit_texts upsert(unnest, 입력 순서 = seq), 그 유닛들의
   * bigram 삭제, 펼친 bigram 삽입(unit_id로 seq 조인). 같은 unitId가 다시 나오면 마지막 본문을 처음 자리에 쓴다(행 단위 upsert와 같은
   * 결과 — 한 문장의 ON CONFLICT는 같은 행을 두 번 갱신하지 못한다). 트랜잭션은 호출자 몫이다.
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
    UnitTextBatch batch = UnitTextBatch.of(chunk);
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
        .param("units", batch.unitIds())
        .param("docs", batch.documentIds())
        .param("partitions", batch.partitions())
        .param("archived", batch.archived())
        .param("texts", batch.texts())
        .param("ctexts", batch.compactTexts())
        .update();
    // seq 배열을 InitPlan으로 먼저 만들어 unit_bigrams_unit 색인 조회로 고정한다. 측정한 사실: IN (SELECT …) 형태에서는
    // SearchScaleProbeTest 5,000유닛 적재 중 시트 문서(300유닛) 하나의 삭제가 305ms → 967ms로 표와 함께 늘었고, 이 형태로는
    // 4–8ms로 일정했다. 그때의 실행 계획은 남기지 않았다 — 별도 EXPLAIN 재현(20만 × 30 bigram)에서는 옛 형태도 색인을 썼다.
    jdbc.sql(
            """
            DELETE FROM unit_bigrams WHERE space_id = :space
              AND unit_seq = ANY(ARRAY(SELECT seq FROM unit_texts WHERE space_id = :space AND unit_id = ANY(:units)))
            """)
        .param("space", space.value())
        .param("units", batch.unitIds())
        .update();
    if (!batch.hasBigrams()) return;
    jdbc.sql(
            """
            INSERT INTO unit_bigrams (space_id, unit_seq, bigram, cnt)
            SELECT :space, u.seq, t.b, t.c
            FROM unnest(:bigramUnits, :bigrams, :counts) AS t(unit_id, b, c)
            JOIN unit_texts u ON u.space_id = :space AND u.unit_id = t.unit_id
            """)
        .param("space", space.value())
        .param("bigramUnits", batch.bigramUnits())
        .param("bigrams", batch.bigrams())
        .param("counts", batch.counts())
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
