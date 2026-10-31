package io.scos.engine;

import java.util.List;

/** search 결과: 레시피 순서의 앞에서 limit개, 공간 최신성, 재정렬 결과, 적용된 계획. */
public record SearchResult(
    List<ContextHit> hits, Freshness freshness, RerankStatus rerank, AppliedPlan applied) {
  public SearchResult {
    hits = List.copyOf(hits);
  }
}
