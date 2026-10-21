package io.scos.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.engine.internal.search.ContextAssembler;
import io.scos.engine.internal.search.DocumentReader;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.internal.search.LlmReranker;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.RelatedDocuments;
import io.scos.engine.internal.search.Reranker;
import io.scos.engine.internal.search.SearchMetrics;
import io.scos.engine.internal.search.SearchSettings;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ContextEngine} 조립(단계 2 §1.1). 필수는 units·keywords·vectors·embedder다. build는 DB에 접근하지 않는다.
 * 검색 값은 {@link SearchSettings#DEFAULT}로 고정한다(0.1.0은 튜닝 값을 공개하지 않는다). 시작점은 {@link
 * ContextEngine#builder()}다.
 */
public final class ContextEngineBuilder {
  /** 지정하지 않은 키워드 채널 실행기의 스레드 수 — stdio 요청은 한 번에 하나라 둘이면 남는다. */
  static final int DEFAULT_LEXICAL_THREADS = 2;

  private UnitStore units;
  private KeywordIndex keywords;
  private VectorIndex vectors;
  private Embedder embedder;
  private Reranker reranker = Reranker.DISABLED;
  private SpaceId defaultSpace = SpaceId.LEGACY;
  private Clock clock = Clock.systemUTC();
  private MeterRegistry meterRegistry;
  private Executor lexicalExecutor;

  public ContextEngineBuilder units(UnitStore units) {
    this.units = Objects.requireNonNull(units, "units");
    return this;
  }

  public ContextEngineBuilder keywords(KeywordIndex keywords) {
    this.keywords = Objects.requireNonNull(keywords, "keywords");
    return this;
  }

  public ContextEngineBuilder vectors(VectorIndex vectors) {
    this.vectors = Objects.requireNonNull(vectors, "vectors");
    return this;
  }

  public ContextEngineBuilder embedder(Embedder embedder) {
    this.embedder = Objects.requireNonNull(embedder, "embedder");
    return this;
  }

  /** LLM 재정렬을 켠다. 지정하지 않으면 재정렬 없음이다. 매퍼를 받는 이유: 재정렬 프롬프트와 캐시 키 바이트가 매퍼 출력에 달려 있다 — 앱의 매퍼를 넘긴다. */
  public ContextEngineBuilder reranker(TextGenerator generator, ObjectMapper json) {
    this.reranker = new LlmReranker(generator, SearchSettings.DEFAULT.rerankTopK(), json);
    return this;
  }

  /** 질의가 공간을 주지 않을 때 쓰는 공간. 기본 {@link SpaceId#LEGACY}. */
  public ContextEngineBuilder defaultSpace(SpaceId space) {
    this.defaultSpace = Objects.requireNonNull(space, "defaultSpace");
    return this;
  }

  public ContextEngineBuilder clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    return this;
  }

  /** 검색 단계 시간·재정렬 결과를 남길 레지스트리. 지정하지 않으면 엔진 전용 {@link SimpleMeterRegistry}다. */
  public ContextEngineBuilder meterRegistry(MeterRegistry registry) {
    this.meterRegistry = Objects.requireNonNull(registry, "meterRegistry");
    return this;
  }

  /**
   * 키워드 채널 실행기(키워드 ∥ 임베딩, DESIGN-PATTERNS §4 — 공용 ForkJoin 금지). 지정하지 않으면 엔진이 데몬 스레드 둘짜리 풀을
   * 소유한다(프로세스 종료를 막지 않는다). 넘긴 실행기의 종료는 호출자 몫이다.
   */
  public ContextEngineBuilder lexicalExecutor(Executor executor) {
    this.lexicalExecutor = Objects.requireNonNull(executor, "lexicalExecutor");
    return this;
  }

  /** 필수 포트가 없으면 {@code IllegalStateException("<name> is required")}. */
  public ContextEngine build() {
    require(units, "units");
    require(keywords, "keywords");
    require(vectors, "vectors");
    require(embedder, "embedder");
    QuestionSearch search =
        new QuestionSearch(
            units,
            keywords,
            vectors,
            embedder,
            reranker,
            SearchSettings.DEFAULT,
            clock,
            SearchMetrics.of(meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry),
            lexicalExecutor == null ? ownedLexicalExecutor() : lexicalExecutor);
    return new DefaultContextEngine(
        defaultSpace,
        search,
        new DocumentReader(units),
        new RelatedDocuments(units, vectors),
        new ContextAssembler(units, search, clock),
        new FreshnessReader(units, clock));
  }

  private static void require(Object port, String name) {
    if (port == null) throw new IllegalStateException(name + " is required");
  }

  private static Executor ownedLexicalExecutor() {
    AtomicInteger n = new AtomicInteger();
    return Executors.newFixedThreadPool(
        DEFAULT_LEXICAL_THREADS,
        r -> {
          Thread t = new Thread(r, "search-lexical-" + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }
}
