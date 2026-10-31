package io.scos.engine.internal.search.candidate;

import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.List;

/** 키워드만으로 찾는 조립의 벡터 채널 — 임베딩도 벡터 색인도 부르지 않고 적중이 없다. */
public final class NoVectors implements VectorChannel {
  @Override
  public List<List<ScoredRef>> hits(
      String recipe,
      SpaceId space,
      List<String> texts,
      SearchSettings settings,
      SearchFilter filter) {
    return List.of();
  }
}
