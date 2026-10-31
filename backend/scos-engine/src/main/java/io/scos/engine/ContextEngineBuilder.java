package io.scos.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.engine.internal.access.AccessResolver;
import io.scos.engine.internal.access.AccessScopes;
import io.scos.engine.internal.access.ResolvedScopes;
import io.scos.engine.internal.access.UnrestrictedScopes;
import io.scos.engine.internal.search.ContextAssembler;
import io.scos.engine.internal.search.DocumentReader;
import io.scos.engine.internal.search.Escalation;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.RelatedDocuments;
import io.scos.engine.internal.search.SearchMetrics;
import io.scos.engine.internal.search.candidate.CandidateSource;
import io.scos.engine.internal.search.candidate.EmbeddedVectors;
import io.scos.engine.internal.search.candidate.NoVectors;
import io.scos.engine.internal.search.candidate.VectorChannel;
import io.scos.engine.internal.search.plan.PlanResolver;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.engine.internal.search.refine.ArchivePolicy;
import io.scos.engine.internal.search.refine.DocumentCap;
import io.scos.engine.internal.search.refine.LinkExpansion;
import io.scos.engine.internal.search.refine.TierPriority;
import io.scos.engine.internal.search.rerank.LlmReranker;
import io.scos.engine.internal.search.rerank.Reranker;
import io.scos.engine.internal.search.signal.RecencySignal;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ContextEngine} 조립(단계 2 §1.1). 필수는 units·keywords와 접근 모드, 그리고 embedder 또는 keywordOnly다.
 * vectors는 embedder를 선택할 때만 필수다. build는 DB에 접근하지 않는다. 검색 값은 호출마다 강도 프리셋에서 정한다(튜닝 값은 공개하지 않는다). 시작점은
 * {@link ContextEngine#builder()}다.
 */
public final class ContextEngineBuilder {
  /** 지정하지 않은 키워드 채널 실행기의 스레드 수 — stdio 요청은 한 번에 하나라 둘이면 남는다. */
  static final int DEFAULT_LEXICAL_THREADS = 2;

  private sealed interface AccessConfiguration {
    record Unrestricted() implements AccessConfiguration {}

    record Restricted(IdentityStore identities, SourceFreshness freshness, DomainPolicy domains)
        implements AccessConfiguration {}
  }

  private sealed interface VectorChannelChoice {
    record WithEmbedder(Embedder embedder) implements VectorChannelChoice {}

    record KeywordOnly() implements VectorChannelChoice {}
  }

  private @Nullable UnitStore units;
  private @Nullable UnitMaintenance maintenance;
  private @Nullable AccessConfiguration access;
  private @Nullable KeywordIndex keywords;
  private @Nullable VectorIndex vectors;
  private @Nullable VectorChannelChoice vectorChoice;
  private Reranker reranker = Reranker.DISABLED;
  private SpaceId defaultSpace = SpaceId.LEGACY;
  private Clock clock = Clock.systemUTC();
  private @Nullable MeterRegistry meterRegistry;
  private @Nullable Executor lexicalExecutor;

  public ContextEngineBuilder units(UnitStore units) {
    this.units = Objects.requireNonNull(units, "units");
    return this;
  }

  /** 단일 사용자 모드. 최신성은 공간에 적재된 유닛 시각으로 계산한다. */
  public ContextEngineBuilder unrestricted(UnitMaintenance maintenance) {
    requireUnsetMode();
    this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    this.access = new AccessConfiguration.Unrestricted();
    return this;
  }

  /** 다중 사용자 모드. 유지보수 포트를 보유하지 않고 요청마다 접근 키를 읽는다. */
  public ContextEngineBuilder access(
      IdentityStore identities, SourceFreshness freshness, DomainPolicy domains) {
    requireUnsetMode();
    this.access =
        new AccessConfiguration.Restricted(
            Objects.requireNonNull(identities, "identities"),
            Objects.requireNonNull(freshness, "freshness"),
            Objects.requireNonNull(domains, "domains"));
    return this;
  }

  private void requireUnsetMode() {
    if (access != null) throw new IllegalStateException("access mode set twice");
  }

  public ContextEngineBuilder keywords(KeywordIndex keywords) {
    this.keywords = Objects.requireNonNull(keywords, "keywords");
    return this;
  }

  public ContextEngineBuilder vectors(VectorIndex vectors) {
    this.vectors = Objects.requireNonNull(vectors, "vectors");
    return this;
  }

  /** 질의를 이 임베더로 임베딩해 벡터 채널에서도 찾는다. 마지막으로 부른 {@link #keywordOnly()} 또는 이 설정을 따른다. */
  public ContextEngineBuilder embedder(Embedder embedder) {
    this.vectorChoice =
        new VectorChannelChoice.WithEmbedder(Objects.requireNonNull(embedder, "embedder"));
    return this;
  }

  /** 키워드만으로 찾는다 — 벡터 채널을 조립하지 않아 임베딩도 벡터 색인도 부르지 않는다. 마지막으로 부른 {@link #embedder} 또는 이 설정을 따른다. */
  public ContextEngineBuilder keywordOnly() {
    this.vectorChoice = new VectorChannelChoice.KeywordOnly();
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
    if (access == null)
      throw new IllegalStateException(
          "access mode is required: call unrestricted() or access(...)");
    UnitStore units = require(this.units, "units");
    KeywordIndex keywords = require(this.keywords, "keywords");
    VectorChannelChoice choice = require(vectorChoice, "embedder");
    SearchMetrics metrics =
        SearchMetrics.of(meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry);
    VectorChannel vectorChannel = vectorChannel(choice, metrics);
    FreshnessReader freshness = freshness(access);
    Scorer scorer = new Scorer(List.of(new RecencySignal()));
    QuestionSearch search =
        new QuestionSearch(
            new CandidateSource(
                units,
                keywords,
                vectorChannel,
                metrics,
                lexicalExecutor == null ? ownedLexicalExecutor() : lexicalExecutor),
            new ArchivePolicy(),
            new LinkExpansion(units),
            new DocumentCap(),
            new TierPriority(),
            reranker,
            clock,
            metrics,
            freshness,
            scorer,
            new Escalation(new PlanResolver()));
    return new DefaultContextEngine(
        defaultSpace,
        search,
        new DocumentReader(units),
        relatedDocuments(choice, units),
        new ContextAssembler(units, search, clock, scorer),
        freshness,
        clock,
        new PlanResolver(),
        scopes(access));
  }

  private VectorChannel vectorChannel(VectorChannelChoice choice, SearchMetrics metrics) {
    return switch (choice) {
      case VectorChannelChoice.WithEmbedder with ->
          new EmbeddedVectors(require(vectors, "vectors"), with.embedder(), metrics);
      case VectorChannelChoice.KeywordOnly ignored -> new NoVectors();
    };
  }

  private RelatedDocuments relatedDocuments(VectorChannelChoice choice, UnitStore units) {
    return switch (choice) {
      case VectorChannelChoice.WithEmbedder ignored ->
          new RelatedDocuments(units, require(vectors, "vectors"));
      case VectorChannelChoice.KeywordOnly ignored -> new RelatedDocuments(units);
    };
  }

  private AccessScopes scopes(AccessConfiguration access) {
    return switch (access) {
      case AccessConfiguration.Unrestricted ignored -> new UnrestrictedScopes();
      case AccessConfiguration.Restricted restricted ->
          new ResolvedScopes(new AccessResolver(restricted.identities(), restricted.domains()));
    };
  }

  private FreshnessReader freshness(AccessConfiguration access) {
    return switch (access) {
      case AccessConfiguration.Unrestricted ignored ->
          new FreshnessReader(require(maintenance, "maintenance"), clock);
      case AccessConfiguration.Restricted restricted ->
          new FreshnessReader(Optional.empty(), Optional.of(restricted.freshness()), clock);
    };
  }

  private static <T> T require(@Nullable T port, String name) {
    if (port == null) throw new IllegalStateException(name + " is required");
    return port;
  }

  private static Executor ownedLexicalExecutor() {
    return Executors.newFixedThreadPool(
        DEFAULT_LEXICAL_THREADS,
        Thread.ofPlatform().name("search-lexical-", 1).daemon(true).factory());
  }
}
