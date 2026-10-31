package io.scos.engine.internal.projection;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.EmbeddingCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 임베더로 100개씩 임베딩한다 — 배치마다 캐시를 먼저 본다({@link CachedEmbeddings}). */
public final class EmbedderUnitEmbedding implements UnitEmbedding {
  /** 캐시 조회와 유료 호출의 작업량을 같은 100유닛 경계에서 기록한다. */
  static final int EMBED_BATCH = 100;

  private final EmbeddingGeneration generation;
  private final CachedEmbeddings embeddings;

  /**
   * 유닛 임베딩에 쓸 임베더와 캐시를 받는다.
   *
   * @param cache 임베딩 캐시(F9, ADR-0026) — 같은 세대·같은 입력의 벡터가 있으면 그 입력은 임베더에 보내지 않는다
   */
  public EmbedderUnitEmbedding(Embedder embedder, EmbeddingCache cache) {
    this.generation = EmbeddingGenerationCheck.generationOf(embedder);
    this.embeddings = new CachedEmbeddings(embedder, cache, generation.value());
  }

  /** 임베딩 입력: aliases 없으면 `title\ncontent`(기존 임베딩과 바이트 동일). */
  static String inputOf(ContextUnit u) {
    return u.aliases().isEmpty()
        ? u.title() + "\n" + u.content()
        : u.title() + "\n" + String.join("\n", u.aliases()) + "\n" + u.content();
  }

  @Override
  public EmbeddingGeneration generation() {
    return generation;
  }

  @Override
  public List<ContextUnit> attach(List<ContextUnit> units, Tally tally) {
    List<ContextUnit> out = new ArrayList<>(units.size());
    for (int from = 0; from < units.size(); from += EMBED_BATCH) {
      List<ContextUnit> batch = units.subList(from, Math.min(units.size(), from + EMBED_BATCH));
      CachedEmbeddings.Batch vectors =
          embeddings.vectorsOf(batch.stream().map(EmbedderUnitEmbedding::inputOf).toList());
      tally.recordBatch(vectors.fromCache(), batch.size() - vectors.fromCache());
      for (int i = 0; i < batch.size(); i++)
        out.add(UnitState.withEmbedding(batch.get(i), Optional.of(vectors.vectors().get(i))));
    }
    return List.copyOf(out);
  }
}
