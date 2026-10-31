package io.scos.engine.internal.search.candidate;

import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.List;

/** 후보 생성의 벡터 채널. 질의 텍스트마다 적중 목록을 돌려준다. */
public interface VectorChannel {
  /**
   * 질의마다 벡터 적중 목록을 얻는다.
   *
   * @param recipe 지표 태그(search | assemble)
   * @param texts 원질의와 변형 질의
   * @return texts와 같은 순서의 적중 목록 — 채널이 없으면 빈 목록이다
   */
  List<List<ScoredRef>> hits(
      String recipe,
      SpaceId space,
      List<String> texts,
      SearchSettings settings,
      SearchFilter filter);
}
