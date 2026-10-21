package io.scos.engine.internal.search;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import java.util.List;

/**
 * 재정렬 정책(ADR-0003): LLM({@link LlmReranker}) 또는 비활성({@link #DISABLED}, Null Object). 결과는 후보 +
 * sealed {@link RerankOutcome}.
 */
public interface Reranker {
  record Result(List<Candidate> candidates, RerankOutcome outcome) {}

  Result rerank(String query, List<Candidate> candidates);

  /** 비활성: 원 순서 그대로, Skipped로 보고. */
  Reranker DISABLED =
      (query, candidates) -> new Result(candidates, new RerankOutcome.Skipped("disabled"));
}
