package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.RunHistoryFreshness;
import io.scos.collection.internal.postgres.PostgresRunStore;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.internal.search.candidate.CandidateSource;
import io.scos.engine.internal.search.candidate.EmbeddedVectors;
import io.scos.engine.internal.search.plan.PlanResolver;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.engine.internal.search.refine.ArchivePolicy;
import io.scos.engine.internal.search.refine.DocumentCap;
import io.scos.engine.internal.search.refine.LinkExpansion;
import io.scos.engine.internal.search.refine.TierPriority;
import io.scos.engine.internal.search.rerank.Reranker;
import io.scos.engine.internal.search.signal.RecencySignal;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import io.scos.store.postgres.KeywordStageProbe;
import io.scos.store.postgres.PostgresAccessSeed;
import io.scos.store.postgres.PostgresIdentityStore;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.store.postgres.VectorCompactionProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 접근 필터 측정의 계산과 합성 조건을 검증하고 지연을 기록한다. 성능 수치는 단언하지 않는다. */
@Tag("perf")
@Tag("db")
@DisplayName("소규모 팀의 접근 필터 비용을 합성 코퍼스로 기록한다")
class AccessFilterProbeTest {
  @Test
  @DisplayName("백분위는 정렬된 표본의 nearest-rank로 계산한다")
  void percentilesUseNearestRank() {
    // given
    Samples samples = new Samples(List.of(20L, 1L, 10L, 5L));
    // when
    long p50 = samples.percentile(0.50);
    // then
    assertThat(p50).isEqualTo(5L);
  }

  @Test
  @DisplayName("20만 유닛은 약 6천 문서로 나누어 측정한다")
  void corpusUsesTeamDocumentScale() {
    // given
    TeamCorpus corpus = new TeamCorpus(200_000);
    // when
    int documents = corpus.documents();
    // then
    assertThat(documents).isEqualTo(5_883);
  }

  private ProbeScenario scenario;

  @AfterEach
  void closeScenario() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("전량 적재한 팀 코퍼스에서 접근 범위별 지연을 기록한다")
  void recordsAccessFilterLatency() {
    // given
    scenario = new ProbeScenario(Integer.getInteger("scos.perf.units", 200_000));
    // when
    int loaded = scenario.run();
    // then
    assertThat(loaded).isEqualTo(scenario.corpus.units());
  }

  @Test
  @DisplayName("팀 시드는 사용자 20명과 그룹 40개를 문서 공개 비율 70%로 둔다")
  void seedsSmallTeamAccess() {
    // given
    scenario = new ProbeScenario(1_000);
    scenario.load();
    // when
    Map<String, Integer> shape = scenario.teamShape();
    // then
    assertThat(shape)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("users", 20, "groups", 40, "documents", 30, "public", 21));
  }

  @Test
  @DisplayName("무제한·넓은 범위·한 그룹 범위를 세 번씩 측정한다")
  void measuresThreeAccessWidths() {
    // given
    scenario = new ProbeScenario(1_000);
    scenario.load();
    // when
    List<String> labels = scenario.measure();
    // then
    assertThat(labels)
        .containsExactly(
            "unrestricted",
            "broad",
            "narrow",
            "broad",
            "narrow",
            "unrestricted",
            "narrow",
            "unrestricted",
            "broad");
  }

  @Test
  @DisplayName("제한 첫 질의가 비면 준비 실패로 측정을 중단한다")
  void rejectsEmptyRestrictedMeasurement() {
    // given
    scenario = new ProbeScenario(1_000);
    scenario.load();
    scenario.removeDocumentAccess();
    // when·then
    assertThatIllegalStateException()
        .isThrownBy(scenario::measure)
        .withMessage("given 준비 실패: 제한 범위의 첫 질의 결과가 비어 있다");
  }

  static final class ProbeScenario implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AccessFilterProbeTest.class);
    private static final int QUERIES = 24;
    private static final int REPEATS = 3;
    private static final int WARMUP = 1;
    private static final int ROUNDS = 2;
    private static final int SEARCH_LIMIT = 10;
    private static final String BROAD_USER = "user-7";
    private static final String NARROW_USER = "user-0";
    private static final String NARROW_GROUP = "G0";
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
    private static final DomainPolicy DOMAINS =
        new DomainPolicy(
            Set.of("example.com"), Map.of(new SourceInstanceId("slack-a"), "example.com"));
    private static final SpaceId SPACE = new SpaceId("access-probe");
    private static final int READ_POOL_SIZE = 4;
    private static final int USERS = 20;
    private static final int GROUPS = 40;
    private static final int MIN_MEMBERSHIPS = 3;
    private static final int MAX_DOCUMENT_KEYS = 3;
    private static final int PUBLIC_NUMERATOR = 7;
    private static final int PUBLIC_DENOMINATOR = 10;
    private static final int MEMBERSHIP_VARIANTS = 8;
    private static final SourceInstanceId INSTANCE = new SourceInstanceId("slack-a");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExecutorService lexical = Executors.newFixedThreadPool(2);
    private final TeamCorpus corpus;
    private final HikariDataSource pool;
    private final SyntheticCorpus source;

    ProbeScenario(int units) {
      corpus = new TeamCorpus(units);
      source = new SyntheticCorpus(units);
      PostgresSupport.truncateAll();
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(PostgresSupport.jdbcUrl());
      config.setUsername(PostgresSupport.username());
      config.setPassword(PostgresSupport.password());
      config.setMaximumPoolSize(READ_POOL_SIZE);
      config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
      pool = new HikariDataSource(config);
    }

    int run() {
      int loaded = load();
      log.info(
          "RESULT access setup units={} documents={} pool={} dim={} queries={} warmup={} rounds={} repeats={} team={}",
          loaded,
          corpus.documents(),
          READ_POOL_SIZE,
          SyntheticCorpus.DIM,
          QUERIES,
          WARMUP,
          ROUNDS,
          REPEATS,
          teamShape());
      measure();
      return loaded;
    }

    int load() {
      var embedder = new SearchScaleProbeTest.CorpusEmbedder(source, Duration.ZERO);
      var generation =
          EmbeddingGeneration.of(embedder.identity(), EmbeddingGenerationCheck.INPUT_FORMAT);
      var importer =
          new UnitImporter(
              new TransactionTemplate(new DataSourceTransactionManager(pool)),
              PostgresTestAdapters.unitMaintenance(pool),
              PostgresTestAdapters.keywordIndex(pool),
              PostgresTestAdapters.vectorIndex(pool),
              PostgresTestAdapters.spaceMeta(pool),
              generation);
      corpus.forEachDocument(
          source, batch -> importer.importUnits(SPACE, batch, Optional.of(generation)));
      seedAccess();
      JdbcClient.create(pool).sql("VACUUM ANALYZE").update();
      return JdbcClient.create(pool)
          .sql("SELECT count(*) FROM units")
          .query(Integer.class)
          .single();
    }

    List<String> measure() {
      List<Width> widths = widths();
      QuestionSearch search = search();
      List<String> labels = new ArrayList<>();
      for (int repeat = 0; repeat < REPEATS; repeat++) {
        for (int offset = 0; offset < widths.size(); offset++) {
          Width width = widths.get((repeat + offset) % widths.size());
          measureWidth(repeat + 1, width, search);
          labels.add(width.label());
        }
      }
      return List.copyOf(labels);
    }

    private List<Width> widths() {
      return List.of(
          new Width("unrestricted", AccessScope::singleUser),
          new Width("broad", () -> resolved(BROAD_USER)),
          new Width("narrow", this::narrowScope));
    }

    private AccessScope resolved(String user) {
      var keys =
          new PostgresIdentityStore(JdbcClient.create(pool))
              .accessKeysOf(user, DOMAINS)
              .orElseThrow();
      return new AccessScope.Restricted(new Principal(user), keys);
    }

    private AccessScope narrowScope() {
      AccessScope.Restricted resolved = (AccessScope.Restricted) resolved(NARROW_USER);
      AccessKey group = new AccessKey(AccessKey.Kind.GROUP, INSTANCE, NARROW_GROUP);
      if (!resolved.keys().contains(group))
        throw new IllegalStateException("given 준비 실패: 좁은 사용자는 G0의 구성원이다");
      // 모든 사용자의 구성원 수(3–10)는 유지하고, 폭 실험에는 실제 키 중 그룹 하나만 쓴다.
      return new AccessScope.Restricted(resolved.principal(), Set.of(group));
    }

    private QuestionSearch search() {
      var units = PostgresTestAdapters.unitStore(pool);
      var maintenance = PostgresTestAdapters.unitMaintenance(pool);
      var jdbc = JdbcClient.create(pool);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
      var freshness =
          new FreshnessReader(
              Optional.of(maintenance),
              Optional.of(new RunHistoryFreshness(new PostgresRunStore(jdbc, tx))),
              CLOCK);
      return new QuestionSearch(
          new CandidateSource(
              units,
              PostgresTestAdapters.keywordIndex(pool),
              new EmbeddedVectors(
                  PostgresTestAdapters.vectorIndex(pool),
                  new SearchScaleProbeTest.CorpusEmbedder(source, Duration.ZERO),
                  SearchMetrics.of(registry)),
              SearchMetrics.of(registry),
              lexical),
          new ArchivePolicy(),
          new LinkExpansion(units),
          new DocumentCap(),
          new TierPriority(),
          Reranker.DISABLED,
          CLOCK,
          SearchMetrics.of(registry),
          freshness,
          new Scorer(List.of(new RecencySignal())),
          new Escalation(new PlanResolver()));
    }

    private void measureWidth(int repeat, Width width, QuestionSearch search) {
      List<String> queries = source.queries(QUERIES);
      warmUp(queries, width, search);
      Timer acquire = registry.get("hikaricp.connections.acquire").timer();
      Timer usage = registry.get("hikaricp.connections.usage").timer();
      long before = acquire.count();
      double usageBefore = usage.totalTime(TimeUnit.MILLISECONDS);
      Samples searches = measured(queries, ROUNDS, q -> invoke(search, width, q));
      double connections = (acquire.count() - before) / (double) searches.nanos().size();
      double held =
          (usage.totalTime(TimeUnit.MILLISECONDS) - usageBefore) / searches.nanos().size();
      AccessScope scope = width.scope().get();
      SearchFilter filter = new SearchFilter(true, null, scope);
      VectorIndex vectors = PostgresTestAdapters.vectorIndex(pool);
      Samples vector = vectorLatency(queries, filter, vectors);
      Samples keys =
          measured(
              userIds(),
              1,
              u -> new PostgresIdentityStore(JdbcClient.create(pool)).accessKeysOf(u, DOMAINS));
      Map<String, Integer> reached = new LinkedHashMap<>();
      Map<String, Integer> settled = new LinkedHashMap<>();
      observeStages(queries, filter, reached, settled);
      log.info(
          "RESULT access repeat={} scope={} search={} vector={} accessKeys={} connections/request={} connectionMs/request={} reached={} settled={}",
          repeat,
          width.label(),
          searches,
          vector,
          keys,
          connections,
          held,
          reached,
          settled);
      if (width.label().equals("broad"))
        measureCompaction(repeat, queries, filter, vectors, vector);
    }

    private void warmUp(List<String> queries, Width width, QuestionSearch search) {
      for (int warm = 0; warm < WARMUP; warm++)
        for (String query : queries) {
          QuestionSearch.Result result = invoke(search, width, query);
          if (warm == 0
              && query.equals(queries.getFirst())
              && result.access() instanceof AccessScope.Restricted
              && result.candidates().isEmpty())
            throw new IllegalStateException("given 준비 실패: 제한 범위의 첫 질의 결과가 비어 있다");
        }
    }

    private Samples vectorLatency(List<String> queries, SearchFilter filter, VectorIndex vectors) {
      return measured(
          queries,
          1,
          q ->
              vectors.search(
                  SPACE,
                  List.of(source.queryEmbedding(q)),
                  SearchSettings.DEFAULT.vectorK(),
                  filter));
    }

    private QuestionSearch.Result invoke(QuestionSearch search, Width width, String query) {
      return search.search(
          width.scope().get(), SPACE, query, SearchSettingsBuilder.DEFAULT_PLAN, SEARCH_LIMIT);
    }

    private void observeStages(
        List<String> queries,
        SearchFilter filter,
        Map<String, Integer> reached,
        Map<String, Integer> settled) {
      var probe = new KeywordStageProbe(pool);
      for (String q : queries) {
        var observation = probe.observe(SPACE, q, SearchSettings.DEFAULT.lexicalK(), filter);
        observation.reached().forEach(level -> reached.merge(level, 1, Integer::sum));
        settled.merge(observation.settled(), 1, Integer::sum);
      }
    }

    private void measureCompaction(
        int repeat,
        List<String> queries,
        SearchFilter filter,
        VectorIndex vectors,
        Samples ordinary) {
      var probe = new VectorCompactionProbe(pool);
      for (String query : queries) {
        double[] vector = source.queryEmbedding(query);
        if (!probe
            .search(SPACE, vector, SearchSettings.DEFAULT.vectorK(), filter)
            .equals(
                vectors
                    .search(SPACE, List.of(vector), SearchSettings.DEFAULT.vectorK(), filter)
                    .getFirst())) throw new IllegalStateException("given 준비 실패: 압축 0건이면 탐침 결과가 같다");
      }
      Samples alternative =
          measured(
              queries,
              1,
              q ->
                  probe.search(
                      SPACE, source.queryEmbedding(q), SearchSettings.DEFAULT.vectorK(), filter));
      log.info(
          "RESULT access compaction repeat={} ordinary={} notExists={}",
          repeat,
          ordinary,
          alternative);
    }

    private Samples measured(List<String> inputs, int rounds, Consumer<String> call) {
      List<Long> nanos = new ArrayList<>();
      for (int round = 0; round < rounds; round++)
        for (String input : inputs) {
          long started = System.nanoTime();
          call.accept(input);
          nanos.add(System.nanoTime() - started);
        }
      return new Samples(nanos);
    }

    private List<String> userIds() {
      List<String> ids = new ArrayList<>();
      for (int u = 0; u < USERS; u++) ids.add("user-" + u);
      return ids;
    }

    private record Width(String label, Supplier<AccessScope> scope) {}

    private void seedAccess() {
      List<User> users = new ArrayList<>();
      List<Identity> identities = new ArrayList<>();
      List<Link> links = new ArrayList<>();
      Map<Integer, Set<String>> members = new LinkedHashMap<>();
      for (int g = 0; g < GROUPS; g++) members.put(g, new HashSet<>());
      for (int u = 0; u < USERS; u++) {
        String id = "user-" + u;
        users.add(new User(id, id + "@example.com", true));
        identities.add(new Identity(INSTANCE, id, false, false));
        links.add(new Link(INSTANCE, id, id, "admin"));
        for (int g = 0; g < MIN_MEMBERSHIPS + u % MEMBERSHIP_VARIANTS; g++)
          members.get((u * 2 + g) % GROUPS).add(id);
      }
      List<Group> groups =
          members.entrySet().stream()
              .map(e -> new Group(INSTANCE, "G" + e.getKey(), e.getValue()))
              .toList();
      new PostgresAccessSeed(pool)
          .seed(new AccessDirectory(users, identities, links, groups, Set.of()));
      seedDocumentAcls();
    }

    private void seedDocumentAcls() {
      JdbcClient.create(pool)
          .sql(
              """
          INSERT INTO document_acl (space_id, document_id, status, keys, fingerprint, observed_at)
          SELECT :space, 'team-' || n, 'ok',
            ARRAY(SELECT CASE WHEN slot = 0 AND n % :publicDenominator < :publicNumerator
              THEN 'domain:slack-a:example.com'
              ELSE 'group:slack-a:G' || ((n * :maxKeys + slot) % :groups) END
              FROM generate_series(0, n % :maxKeys) slot),
            'synthetic', '2026-10-26T00:00:00Z'::timestamptz
          FROM generate_series(0, :last) n
          """)
          .param("space", SPACE.value())
          .param("last", corpus.documents() - 1)
          .param("groups", GROUPS)
          .param("maxKeys", MAX_DOCUMENT_KEYS)
          .param("publicNumerator", PUBLIC_NUMERATOR)
          .param("publicDenominator", PUBLIC_DENOMINATOR)
          .update();
    }

    void removeDocumentAccess() {
      JdbcClient.create(pool).sql("DELETE FROM document_acl").update();
    }

    Map<String, Integer> teamShape() {
      JdbcClient jdbc = JdbcClient.create(pool);
      return Map.of(
          "users",
          jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single(),
          "groups",
          jdbc.sql("SELECT count(*) FROM source_groups").query(Integer.class).single(),
          "documents",
          jdbc.sql("SELECT count(*) FROM document_acl").query(Integer.class).single(),
          "public",
          jdbc.sql(
                  "SELECT count(*) FROM document_acl WHERE keys @> ARRAY['domain:slack-a:example.com']")
              .query(Integer.class)
              .single());
    }

    @Override
    public void close() {
      lexical.shutdownNow();
      pool.close();
      registry.close();
    }
  }

  record TeamCorpus(int units) {
    // Task a10: 20만 유닛을 약 6천 문서로 나눈다.
    private static final int UNITS_PER_DOCUMENT = 34;

    int documents() {
      return Math.ceilDiv(units, UNITS_PER_DOCUMENT);
    }

    void forEachDocument(SyntheticCorpus source, Consumer<List<ContextUnit>> sink) {
      List<ContextUnit> batch = new ArrayList<>();
      int ordinal = 0;
      for (int d = 0; d < source.documents(); d++) {
        for (ContextUnit unit : source.document(d)) {
          int document = ordinal++ / UNITS_PER_DOCUMENT;
          batch.add(
              ContextUnits.from(unit)
                  .inDocument("team-" + document)
                  .source("slack-a")
                  .linksTo("team-" + (document + 1) % documents())
                  .build());
          if (batch.size() == UNITS_PER_DOCUMENT) {
            sink.accept(List.copyOf(batch));
            batch.clear();
          }
        }
      }
      if (!batch.isEmpty()) sink.accept(List.copyOf(batch));
    }
  }

  record Samples(List<Long> nanos) {
    Samples {
      nanos = List.copyOf(nanos);
    }

    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "p50=%.3fms,p95=%.3fms,n=%d",
          percentile(0.50) / 1e6,
          percentile(0.95) / 1e6,
          nanos.size());
    }

    long percentile(double rank) {
      long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
      return sorted[(int) Math.ceil(rank * sorted.length) - 1];
    }
  }
}
