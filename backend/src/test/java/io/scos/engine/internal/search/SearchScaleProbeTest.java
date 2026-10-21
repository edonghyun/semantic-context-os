package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영 규모 프로브(F3 T3.2, ts-replacement-gap G9): 합성 코퍼스({@link SyntheticCorpus}, 기본 20만 유닛, 시트형 91%)를
 * 운영 적재 경로(UnitImporter, 문서마다 한 트랜잭션)로 넣고 적재 시간, 키워드·정확 벡터 스캔·search_context 레시피의 질의 지연 p50/p95,
 * 레시피 단계별 평균을 로그로 남긴다. 게이트가 아니다 — 수치는 VERIFICATION "성능"에 부하 조건과 함께 적는다. 연결은 운영과 같은 Hikari 풀(최대
 * 6)이다.
 *
 * <p>크기는 {@code -Dscos.perf.units=N}으로 바꾼다(perfTest 태스크가 넘긴다). 태그 perf: dbTest에서 빠지고 {@code
 * ./gradlew perfTest}로만 돈다.
 */
@Tag("db")
@Tag("perf")
@DisplayName("운영 규모 프로브 — 적재·질의 지연을 기록한다(G9, 게이트 아님)")
class SearchScaleProbeTest {
  static final Logger log = LoggerFactory.getLogger(SearchScaleProbeTest.class);
  static final SpaceId S = new SpaceId("scale");
  static final int UNITS = Integer.getInteger("scos.perf.units", 200_000);
  static final int QUERIES = 24;
  static final int WARMUP_ROUNDS = 1;

  /** 짝수여야 질의마다 순차·병렬이 먼저 도는 횟수가 같다(ABBA, F3 재확인 R-m1). */
  static final int MEASURED_ROUNDS = 4;

  static final int POOL_SIZE = 6;
  static final SearchFilter CANDIDATES = new SearchFilter(true, null);
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

  /**
   * 질의 임베딩 한 번(질의 + 변형 배치)의 주입 지연. 0 = 카세트처럼 지연 없음(스레드 전환 비용만 보인다), 376ms = 운영 Gemini 질의 임베딩 왕복의
   * 기록값(운영 규모 데이터 평가 보고 "warm p50 1.17s(질의 임베딩 API 376ms …)"). 실 API는 부르지 않는다(F3 리뷰 I3).
   */
  static final List<Duration> EMBED_LATENCIES = List.of(Duration.ZERO, Duration.ofMillis(376));

  final SyntheticCorpus corpus = new SyntheticCorpus(UNITS);
  final HikariDataSource pool = pool();
  final UnitStore units = PostgresTestAdapters.unitStore(pool);
  final KeywordIndex keywords = PostgresTestAdapters.keywordIndex(pool);
  final VectorIndex vectors = PostgresTestAdapters.vectorIndex(pool);

  /** 운영 조립(McpConfiguration)과 같은 크기의 키워드 채널 실행기. */
  final ExecutorService lexicalExecutor = Executors.newFixedThreadPool(2);

  /** p50·p95(ms, nearest-rank)와 표본 수. */
  record Latency(double p50, double p95, int samples) {
    static Latency of(List<Long> nanos) {
      long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
      return new Latency(rank(sorted, 0.50), rank(sorted, 0.95), sorted.length);
    }

    private static double rank(long[] sorted, double p) {
      int i = (int) Math.ceil(p * sorted.length) - 1;
      return sorted[Math.max(i, 0)] / 1e6;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "p50=%.1fms p95=%.1fms n=%d", p50, p95, samples);
    }
  }

  /**
   * 같은 임베더 지연에서 search_context를 키워드 채널 순차(호출 스레드 = T3.3 이전 동작)와 병렬(전용 실행기)로 잰 결과. 질의마다 둘을 이어 돌리되
   * 순서를 (라운드 + 질의 번호)의 홀짝으로 바꾼다(ABBA — 먼저 돈 쪽이 올린 캐시를 뒤쪽이 덕 보는 편향을 상쇄, F3 리뷰 I2). 순서별 표본도 따로 남겨 그
   * 편향의 크기를 본다. stages = 병렬 레시피의 단계별 평균(ms, 측정 뒤 한 라운드).
   */
  record RecipeComparison(
      Duration embedLatency,
      Latency sequential,
      Latency parallel,
      Latency sequentialWhenFirst,
      Latency sequentialWhenSecond,
      Latency parallelWhenFirst,
      Latency parallelWhenSecond,
      Map<String, Double> stages) {}

  /** 적재 결과, 시트 몫(행 수), 질의 지연. */
  record ScaleRun(
      int loadedUnits,
      long loadMs,
      Map<String, String> tableSizes,
      Map<String, Long> sheetShare,
      Latency keyword,
      Latency vector,
      List<RecipeComparison> recipes) {}

  static HikariDataSource pool() {
    PostgresSupport.truncateAll();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(POOL_SIZE);
    return new HikariDataSource(config);
  }

  @AfterEach
  void closePool() {
    PostgresSupport.truncateAll();
    pool.close();
    lexicalExecutor.shutdownNow();
  }

  ScaleRun loadAndQuery() {
    long loadMs = load();
    // 방금 넣은 행의 hint bit·가시성 지도를 질의 측정 전에 정리한다(운영은 autovacuum이 끝난 상태) — 없으면 첫 측정이 쓰기 비용을 섞는다
    JdbcClient.create(pool).sql("VACUUM ANALYZE").update();
    Map<String, String> sizes = tableSizes();
    List<String> queries = corpus.queries(QUERIES);
    Latency keyword =
        measure(queries, q -> keywords.search(S, q, SearchSettings.DEFAULT.lexicalK(), CANDIDATES));
    Latency vector =
        measure(
            queries,
            q ->
                vectors.search(
                    S,
                    List.of(corpus.queryEmbedding(q)),
                    SearchSettings.DEFAULT.vectorK(),
                    CANDIDATES));
    List<RecipeComparison> recipes = new ArrayList<>();
    for (Duration latency : EMBED_LATENCIES) recipes.add(compareRecipes(queries, latency));
    return new ScaleRun(countUnits(), loadMs, sizes, sheetShare(), keyword, vector, recipes);
  }

  private RecipeComparison compareRecipes(List<String> queries, Duration embedLatency) {
    QuestionSearch sequential =
        questionSearch(new SimpleMeterRegistry(), Runnable::run, embedLatency);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    QuestionSearch parallel = questionSearch(registry, lexicalExecutor, embedLatency);
    for (int r = 0; r < WARMUP_ROUNDS; r++)
      for (String q : queries) {
        sequential.search(AccessScope.singleUser(), S, q);
        parallel.search(AccessScope.singleUser(), S, q);
      }
    List<Long> seqFirst = new ArrayList<>();
    List<Long> seqSecond = new ArrayList<>();
    List<Long> parFirst = new ArrayList<>();
    List<Long> parSecond = new ArrayList<>();
    for (int r = 0; r < MEASURED_ROUNDS; r++)
      for (int i = 0; i < queries.size(); i++) {
        String q = queries.get(i);
        if ((r + i) % 2 == 0) {
          seqFirst.add(timed(() -> sequential.search(AccessScope.singleUser(), S, q)));
          parSecond.add(timed(() -> parallel.search(AccessScope.singleUser(), S, q)));
        } else {
          parFirst.add(timed(() -> parallel.search(AccessScope.singleUser(), S, q)));
          seqSecond.add(timed(() -> sequential.search(AccessScope.singleUser(), S, q)));
        }
      }
    registry.clear();
    for (String q : queries) parallel.search(AccessScope.singleUser(), S, q);
    return new RecipeComparison(
        embedLatency,
        Latency.of(concat(seqFirst, seqSecond)),
        Latency.of(concat(parFirst, parSecond)),
        Latency.of(seqFirst),
        Latency.of(seqSecond),
        Latency.of(parFirst),
        Latency.of(parSecond),
        stageMeans(registry));
  }

  private static List<Long> concat(List<Long> a, List<Long> b) {
    List<Long> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  /** 시트 문서가 차지하는 행 수(전체 대비) — "시트가 대부분"을 크기 추정이 아니라 행 수로 본다(F3 리뷰 I4). */
  private Map<String, Long> sheetShare() {
    Map<String, Long> out = new LinkedHashMap<>();
    JdbcClient jdbc = JdbcClient.create(pool);
    out.put("units_total", count(jdbc, "SELECT count(*) FROM unit_texts"));
    out.put(
        "units_sheet",
        count(jdbc, "SELECT count(*) FROM unit_texts WHERE document_id LIKE 'sheet-%'"));
    out.put("bigrams_total", count(jdbc, "SELECT count(*) FROM unit_bigrams"));
    out.put(
        "bigrams_sheet",
        count(
            jdbc,
            "SELECT count(*) FROM unit_bigrams b JOIN unit_texts u ON u.seq = b.unit_seq WHERE u.document_id LIKE 'sheet-%'"));
    out.put("bigram_kinds", count(jdbc, "SELECT count(DISTINCT bigram) FROM unit_bigrams"));
    return out;
  }

  private static long count(JdbcClient jdbc, String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  private long load() {
    UnitImporter importer =
        new UnitImporter(
            new TransactionTemplate(new DataSourceTransactionManager(pool)),
            units,
            keywords,
            vectors);
    long t0 = System.nanoTime();
    int step = Math.max(1, corpus.documents() / 10);
    for (int d = 0; d < corpus.documents(); d++) {
      importer.importUnits(S, corpus.document(d));
      if ((d + 1) % step == 0)
        log.info(
            "load {}/{} documents {}ms",
            d + 1,
            corpus.documents(),
            (System.nanoTime() - t0) / 1_000_000);
    }
    return (System.nanoTime() - t0) / 1_000_000;
  }

  private int countUnits() {
    return JdbcClient.create(pool)
        .sql("SELECT count(*) FROM units WHERE space_id = :space")
        .param("space", S.value())
        .query(Integer.class)
        .single();
  }

  private Map<String, String> tableSizes() {
    Map<String, String> out = new LinkedHashMap<>();
    for (String table : List.of("units", "unit_texts", "unit_bigrams", "unit_vectors"))
      out.put(
          table,
          JdbcClient.create(pool)
              .sql("SELECT pg_size_pretty(pg_total_relation_size(CAST(:t AS regclass)))")
              .param("t", table)
              .query(String.class)
              .single());
    return out;
  }

  /** 질의마다 WARMUP_ROUNDS 번 돌린 뒤 MEASURED_ROUNDS 번 잰다. */
  private Latency measure(List<String> queries, Consumer<String> query) {
    for (int r = 0; r < WARMUP_ROUNDS; r++) queries.forEach(query);
    return measureOnly(queries, query);
  }

  private static Latency measureOnly(List<String> queries, Consumer<String> query) {
    List<Long> nanos = new ArrayList<>();
    for (int r = 0; r < MEASURED_ROUNDS; r++)
      for (String q : queries) {
        long t0 = System.nanoTime();
        query.accept(q);
        nanos.add(System.nanoTime() - t0);
      }
    return Latency.of(nanos);
  }

  private static long timed(Runnable call) {
    long t0 = System.nanoTime();
    call.run();
    return System.nanoTime() - t0;
  }

  private QuestionSearch questionSearch(
      SimpleMeterRegistry registry, Executor executor, Duration embedLatency) {
    return new QuestionSearch(
        units,
        keywords,
        vectors,
        new CorpusEmbedder(corpus, embedLatency),
        Reranker.DISABLED,
        SearchSettings.DEFAULT,
        CLOCK,
        SearchMetrics.of(registry),
        executor);
  }

  private static Map<String, Double> stageMeans(SimpleMeterRegistry registry) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (Timer t : registry.find(SearchMetrics.STAGE).tag("recipe", "search").timers())
      out.put(t.getId().getTag("stage"), round(t.mean(TimeUnit.MILLISECONDS)));
    return out;
  }

  private static double round(double ms) {
    return Math.round(ms * 10) / 10.0;
  }

  private void report(ScaleRun run) {
    log.info(
        "RESULT scale units={} documents={} sheetShare={} load={}ms ({} units/s) sizes={} rows={}",
        run.loadedUnits(),
        corpus.documents(),
        SyntheticCorpus.SHEET_SHARE,
        run.loadMs(),
        run.loadMs() == 0 ? 0 : run.loadedUnits() * 1000L / run.loadMs(),
        run.tableSizes(),
        run.sheetShare());
    log.info("RESULT scale keyword {} | vector(exact) {}", run.keyword(), run.vector());
    for (RecipeComparison c : run.recipes())
      log.info(
          "RESULT scale search_context embed={}ms sequential {} | parallel {} | seq first {} second {} | par first {} second {} | stages(mean ms) {}",
          c.embedLatency().toMillis(),
          c.sequential(),
          c.parallel(),
          c.sequentialWhenFirst(),
          c.sequentialWhenSecond(),
          c.parallelWhenFirst(),
          c.parallelWhenSecond(),
          c.stages());
    log.info("RESULT scale queries {}", corpus.queries(QUERIES));
  }

  /** 질의 텍스트 → 코퍼스의 주제 임베딩(공급자 호출 없음). 호출마다 latency만큼 기다려 운영 공급자 왕복을 흉내 낸다. */
  record CorpusEmbedder(SyntheticCorpus corpus, Duration latency) implements Embedder {
    private static final CountDownLatch NEVER = new CountDownLatch(1);

    @Override
    public int dim() {
      return SyntheticCorpus.DIM;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
      try {
        if (!latency.isZero() && NEVER.await(latency.toNanos(), TimeUnit.NANOSECONDS))
          throw new IllegalStateException("지연 게이트는 열리지 않는다");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("임베더 지연 중 인터럽트", e);
      }
      return texts.stream().map(corpus::queryEmbedding).toList();
    }
  }

  @Test
  @DisplayName("합성 유닛의 적재 시간과 질의 지연을 기록한다(측정, 게이트 아님)")
  void measuresLoadAndQueryLatency() {
    // when
    ScaleRun run = loadAndQuery();

    // then: 수치는 기록만 하고, 단언은 전량 적재 하나다
    report(run);
    assertThat(run.loadedUnits()).isEqualTo(corpus.totalUnits());
  }
}
