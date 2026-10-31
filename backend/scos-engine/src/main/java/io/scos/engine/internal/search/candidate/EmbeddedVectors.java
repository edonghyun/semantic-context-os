package io.scos.engine.internal.search.candidate;

import io.scos.engine.internal.search.SearchMetrics;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.List;

/** 질의를 임베딩해 벡터 색인에서 찾는 채널. 최소 점수 미만 적중은 버린다. */
public final class EmbeddedVectors implements VectorChannel {
  private final VectorIndex vectors;
  private final Embedder embedder;
  private final SearchMetrics metrics;

  public EmbeddedVectors(VectorIndex vectors, Embedder embedder, SearchMetrics metrics) {
    this.vectors = vectors;
    this.embedder = embedder;
    this.metrics = metrics;
  }

  @Override
  public List<List<ScoredRef>> hits(
      String recipe,
      SpaceId space,
      List<String> texts,
      SearchSettings settings,
      SearchFilter filter) {
    List<double[]> queryVectors = metrics.stage(recipe, "embed", () -> embedder.embed(texts));
    return metrics.stage(recipe, "vector", () -> search(space, queryVectors, settings, filter));
  }

  private List<List<ScoredRef>> search(
      SpaceId space, List<double[]> queryVectors, SearchSettings s, SearchFilter filter) {
    List<List<ScoredRef>> out = new ArrayList<>();
    for (List<ScoredRef> hits : vectors.search(space, queryVectors, s.vectorK(), filter)) {
      out.add(
          s.vectorMinScore() <= 0
              ? hits
              : hits.stream().filter(h -> h.score() >= s.vectorMinScore()).toList());
    }
    return out;
  }
}
