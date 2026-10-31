package io.scos.engine.internal.projection;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import java.util.List;
import java.util.Optional;

/** 키워드만 쓰는 조립 — 유닛에 벡터를 붙이지 않는다(저장소에 벡터 행이 생기지 않는다). */
public final class KeywordOnlyUnitEmbedding implements UnitEmbedding {
  /** 키워드만 쓰는 공간에 기록되는 세대 — 임베더가 생기면 세대가 달라 거부되고 {@code scos reembed}를 안내한다. */
  private static final EmbeddingGeneration GENERATION =
      EmbeddingGeneration.of(
          new EmbedderIdentity("none", "none", 1), EmbeddingGenerationCheck.INPUT_FORMAT);

  @Override
  public EmbeddingGeneration generation() {
    return GENERATION;
  }

  @Override
  public List<ContextUnit> attach(List<ContextUnit> units, Tally tally) {
    return units.stream().map(u -> UnitState.withEmbedding(u, Optional.empty())).toList();
  }
}
