package io.scos.engine.internal.search;

import io.scos.engine.internal.search.rerank.Reranker;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.KeywordIndex;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

/**
 * 검색 테스트의 {@link QuestionSearch} 조립: 저장소는 {@link SearchFixture}의 메모리 포트, 나머지는 기본값(영벡터 임베더, 재정렬 없음,
 * 고정 시각, 테스트 지표, 호출 스레드에서 도는 키워드 채널). 사례가 다루는 협력자만 바꾼다.
 */
public final class QuestionSearchBuilder {
  /** 검색 테스트의 기본 시각. */
  public static final Clock NOW =
      Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

  private final SearchFixture fx;
  private KeywordIndex keywords;
  private Embedder embedder = FixedEmbedder.zero(2);
  private Reranker reranker = Reranker.DISABLED;
  private Clock clock = NOW;
  private SearchMetrics metrics = TestMetrics.search();
  private Executor lexical = TestExecutors.CALLER_THREAD;

  private QuestionSearchBuilder(SearchFixture fx) {
    this.fx = fx;
    this.keywords = fx.keywords;
  }

  public static QuestionSearchBuilder over(SearchFixture fx) {
    return new QuestionSearchBuilder(fx);
  }

  public QuestionSearchBuilder keywords(KeywordIndex keywords) {
    this.keywords = keywords;
    return this;
  }

  public QuestionSearchBuilder embedder(Embedder embedder) {
    this.embedder = embedder;
    return this;
  }

  public QuestionSearchBuilder reranker(Reranker reranker) {
    this.reranker = reranker;
    return this;
  }

  public QuestionSearchBuilder clock(Clock clock) {
    this.clock = clock;
    return this;
  }

  public QuestionSearchBuilder metrics(SearchMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  /** 키워드 채널을 이 실행기에서 돌린다(운영 조립처럼 별 스레드). */
  public QuestionSearchBuilder lexicalOn(Executor lexical) {
    this.lexical = lexical;
    return this;
  }

  public QuestionSearch build() {
    return QuestionSearches.of(
        fx.units,
        fx.maintenance,
        keywords,
        fx.vectors,
        embedder,
        reranker,
        clock,
        metrics,
        lexical);
  }
}
