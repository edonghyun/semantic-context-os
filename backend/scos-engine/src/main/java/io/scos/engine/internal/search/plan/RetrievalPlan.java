package io.scos.engine.internal.search.plan;

import io.scos.engine.Effort;

/** 한 번의 검색 호출이 따르는 계획. 설정은 호출마다 받는다. */
public record RetrievalPlan(
    Effort effort, SearchSettings settings, boolean recencyBoost, boolean rerank) {
  public RetrievalPlan withRecency(boolean on) {
    return new RetrievalPlan(effort, settings, on, rerank);
  }
}
