package io.scos.engine.internal.search;

import io.scos.engine.internal.search.candidate.CandidateSource;
import io.scos.engine.internal.search.candidate.EmbeddedVectors;
import io.scos.engine.internal.search.plan.PlanResolver;
import io.scos.engine.internal.search.refine.ArchivePolicy;
import io.scos.engine.internal.search.refine.DocumentCap;
import io.scos.engine.internal.search.refine.LinkExpansion;
import io.scos.engine.internal.search.refine.TierPriority;
import io.scos.engine.internal.search.rerank.Reranker;
import io.scos.engine.internal.search.signal.RecencySignal;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;

/** 테스트용 기본 조립: 운영 조립(ContextEngineBuilder)과 같은 구성을 한 곳에 둔다. */
public final class QuestionSearches {
  private QuestionSearches() {}

  public static QuestionSearch of(
      UnitStore units,
      UnitMaintenance maintenance,
      KeywordIndex keywords,
      VectorIndex vectors,
      Embedder embedder,
      Reranker reranker,
      Clock clock,
      SearchMetrics metrics,
      Executor lexicalExecutor) {
    return new QuestionSearch(
        new CandidateSource(
            units,
            keywords,
            new EmbeddedVectors(vectors, embedder, metrics),
            metrics,
            lexicalExecutor),
        new ArchivePolicy(),
        new LinkExpansion(units),
        new DocumentCap(),
        new TierPriority(),
        reranker,
        clock,
        metrics,
        new FreshnessReader(maintenance, clock),
        new Scorer(List.of(new RecencySignal())),
        new Escalation(new PlanResolver()));
  }
}
