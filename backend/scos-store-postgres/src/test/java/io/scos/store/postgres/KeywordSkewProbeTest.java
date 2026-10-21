package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 흔한 bigram 편중 프로브(ADR-0014 P-4 부록 "드문 bigram 구동"): 운영처럼 시트형 행(날짜·금액·코드 숫자가 빽빽하다)이 대부분인 합성 코퍼스를 키워드
 * 색인에 넣고, 옛 SQL({@link LegacyKeywordSearch})과 제품 {@link PostgresKeywordIndex}의 질의 지연 p50/p95를 질의
 * 모양마다 나란히 기록한다. 몇몇 bigram("20"·"02"·"00" 등)이 유닛의 70% 넘게 나오게 만든다 — {@code SearchScaleProbeTest}의 어휘
 * 코퍼스에는 이 편중이 없어 운영의 느린 질의("2026년" 7.3s)가 재현되지 않았다. 실데이터는 쓰지 않는다. 게이트가 아니다. 단언은 두 경로의 결과 동일과 편중
 * 성립뿐.
 *
 * <p>크기는 {@code -Dscos.perf.units=N}(perfTest 기본 200,000).
 */
@Tag("db")
@Tag("perf")
@DisplayName("흔한 bigram 편중 코퍼스에서 키워드 질의 지연을 기록한다(게이트 아님)")
class KeywordSkewProbeTest {
  static final Logger log = LoggerFactory.getLogger(KeywordSkewProbeTest.class);
  static final SpaceId S = new SpaceId("skew");
  static final int UNITS = Integer.getInteger("scos.perf.units", 200_000);
  static final double SHEET_SHARE = 0.91;
  static final int UNITS_PER_SHEET = 300;
  static final int BATCH = 2_000;
  static final int K = 40;
  static final int WARMUP = 1;
  static final int ROUNDS = 7;
  static final long SEED = 20261018L;

  /** 운영에서 잰 질의 모양(팀 리드 EXPLAIN ANALYZE 표)과 같은 모양의 합성 질의. */
  static final List<String> QUERIES =
      List.of(
          "정산",
          "배포 일정",
          "데이터",
          "api 서버 배포 절차",
          "상반기 정산 데이터 기준 변경 사항 배포 일정 확인 요청 드립니다",
          "2026년",
          "2026년 정산 데이터 기준",
          "2026년 3월 정산 데이터 기준 변경 및 배포 일정 공유");

  static final String[] STATUS = {
    "정산 완료", "정산 대기", "승인", "반려", "배포 예정", "데이터 검증", "기준 변경", "입금 확인", "출금", "미수금", "거래처 확인",
    "담당자 배정", "보류", "검수 완료", "재요청"
  };
  static final String[] PROSE_WORDS = {
    "정산", "데이터", "기준", "배포", "일정", "서버", "절차", "api", "확인", "요청", "변경", "사항", "공유", "상반기", "하반기",
        "검토", "회의",
    "결과", "보고", "장애", "대응", "계정", "권한", "운영", "정책", "적용", "예정", "완료", "드립니다", "부탁", "관련", "문서",
        "작성", "수정"
  };

  final HikariDataSource pool = pool();
  final PostgresKeywordIndex product = new PostgresKeywordIndex(JdbcClient.create(pool));
  final LegacyKeywordSearch legacy = new LegacyKeywordSearch(JdbcClient.create(pool), false);
  final LegacyKeywordSearch oracle = new LegacyKeywordSearch(JdbcClient.create(pool), true);
  final JdbcClient jdbc = JdbcClient.create(pool);

  static HikariDataSource pool() {
    PostgresSupport.truncateAll();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(2);
    return new HikariDataSource(config);
  }

  @AfterEach
  void closePool() {
    PostgresSupport.truncateAll();
    pool.close();
  }

  /** p50·p95(ms, nearest-rank). */
  record Latency(double p50, double p95) {
    static Latency of(List<Long> nanos) {
      long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
      return new Latency(rank(sorted, 0.50), rank(sorted, 0.95));
    }

    private static double rank(long[] sorted, double p) {
      return sorted[Math.max((int) Math.ceil(p * sorted.length) - 1, 0)] / 1e6;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "p50=%.1fms p95=%.1fms", p50, p95);
    }
  }

  /** 질의 하나의 전·후 지연과 두 경로 결과가 같은지. */
  record QueryRun(
      String query, Latency legacy, Latency product, KeywordPlan.Level level, boolean identical) {}

  @Test
  @DisplayName("편중 코퍼스의 질의 모양마다 옛 SQL과 제품 경로의 지연을 기록한다")
  void recordsLatencyPerQueryShape() {
    // given
    long loadMs = load();
    jdbc.sql("VACUUM ANALYZE").update();
    logSkew();

    // when
    List<QueryRun> runs = new ArrayList<>();
    for (String q : QUERIES) runs.add(measure(q));

    // then
    log.info("RESULT skew units={} loadMs={}", UNITS, loadMs);
    for (QueryRun r : runs)
      log.info(
          "RESULT skew query=\"{}\" legacy {} | product {} level={} | identical={}",
          r.query(),
          r.legacy(),
          r.product(),
          r.level(),
          r.identical());
    assertThat(runs).allMatch(QueryRun::identical);
  }

  private QueryRun measure(String q) {
    Runnable before = () -> legacy.search(S, q, K, SearchFilter.NONE);
    Runnable after = () -> product.search(S, q, K, SearchFilter.NONE);
    List<ScoredRef> expected = oracle.search(S, q, K, SearchFilter.NONE);
    PostgresKeywordIndex.Answer answer = product.answer(S, q, K, SearchFilter.NONE);
    boolean identical = expected.equals(answer.refs());
    for (int w = 0; w < WARMUP; w++) {
      before.run();
      after.run();
    }
    List<Long> b = new ArrayList<>();
    List<Long> a = new ArrayList<>();
    for (int r = 0; r < ROUNDS; r++) {
      // 순서를 번갈아 먼저 돈 쪽이 올린 캐시의 편향을 상쇄한다
      if (r % 2 == 0) {
        b.add(timed(before));
        a.add(timed(after));
      } else {
        a.add(timed(after));
        b.add(timed(before));
      }
    }
    return new QueryRun(q, Latency.of(b), Latency.of(a), answer.level(), identical);
  }

  private static long timed(Runnable s) {
    long t0 = System.nanoTime();
    s.run();
    return System.nanoTime() - t0;
  }

  private long load() {
    long t0 = System.nanoTime();
    SplittableRandom random = new SplittableRandom(SEED);
    int sheetUnits = (int) Math.round(UNITS * SHEET_SHARE);
    List<IndexedText> batch = new ArrayList<>(BATCH);
    for (int i = 0; i < UNITS; i++) {
      batch.add(i < sheetUnits ? sheetRow(i, random) : prose(i, random));
      if (batch.size() == BATCH) {
        product.upsert(S, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) product.upsert(S, batch);
    return (System.nanoTime() - t0) / 1_000_000;
  }

  /** 시트 행: 날짜·금액·코드·수량 셀이 빽빽하고 상태 낱말 하나. 1.5%의 행에 "2026년 N월"(드문 토큰, 흔한 bigram으로만 이루어지지 않는다). */
  static IndexedText sheetRow(int i, SplittableRandom r) {
    int doc = i / UNITS_PER_SHEET;
    int row = i % UNITS_PER_SHEET + 1;
    StringBuilder c = new StringBuilder();
    c.append("A").append(row).append(": ").append(date(r));
    c.append("\nB")
        .append(row)
        .append(": ")
        .append(String.format(Locale.ROOT, "%,d", r.nextInt(1, 50_000) * 1_000));
    c.append("\nC")
        .append(row)
        .append(": ")
        .append(String.format(Locale.ROOT, "INV-%d-%05d", 2024 + r.nextInt(3), r.nextInt(100_000)));
    c.append("\nD").append(row).append(": ").append(STATUS[r.nextInt(STATUS.length)]);
    if (r.nextInt(1000) < 15) c.append(" 2026년 ").append(1 + r.nextInt(12)).append("월");
    c.append("\nE").append(row).append(": ").append(r.nextInt(10_000));
    c.append("\nF").append(row).append(": ").append(date(r));
    return new IndexedText(
        "sheet-" + doc + "-" + row,
        "sheet-" + doc,
        "시트 " + doc + " / 범위 A" + row + ":F" + row,
        c.toString(),
        "/",
        false);
  }

  /** 산문: 업무 낱말 문장. 10%에 "2026년". */
  static IndexedText prose(int i, SplittableRandom r) {
    StringBuilder c = new StringBuilder();
    if (r.nextInt(10) == 0) c.append("2026년 ");
    for (int w = 0; w < 40; w++)
      c.append(w == 0 ? "" : " ").append(PROSE_WORDS[r.nextInt(PROSE_WORDS.length)]);
    return new IndexedText("prose-" + i, "prose-" + i / 10, "문서 " + i, c.toString(), "/", false);
  }

  private static String date(SplittableRandom r) {
    return String.format(
        Locale.ROOT, "%d-%02d-%02d", 2024 + r.nextInt(3), 1 + r.nextInt(12), 1 + r.nextInt(28));
  }

  /** 편중 확인: 가장 흔한 bigram들의 유닛 비율, 숫자-숫자 bigram 행 비율. */
  private void logSkew() {
    long units = jdbc.sql("SELECT count(*) FROM unit_texts").query(Long.class).single();
    Map<String, Long> top = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT bigram, count(*) AS n FROM unit_bigrams GROUP BY bigram ORDER BY n DESC LIMIT 8")
        .query(
            rs -> {
              top.put(rs.getString("bigram"), rs.getLong("n"));
            });
    double digitShare =
        jdbc.sql(
                "SELECT avg(CASE WHEN bigram ~ '^[0-9][0-9]$' THEN 1.0 ELSE 0 END) FROM unit_bigrams")
            .query(Double.class)
            .single();
    long rows = jdbc.sql("SELECT count(*) FROM unit_bigrams").query(Long.class).single();
    long rare =
        jdbc.sql("SELECT count(*) FROM unit_bigrams WHERE bigram = '6년'")
            .query(Long.class)
            .single();
    log.info(
        "RESULT skew units={} bigramRows={} digitDigitShare={} top={} df(6년)={}",
        units,
        rows,
        String.format(Locale.ROOT, "%.3f", digitShare),
        top,
        rare);
    assertThat(top.values().iterator().next())
        .as("편중: 최빈 bigram이 유닛의 70% 넘게")
        .isGreaterThan(units * 7 / 10);
  }
}
