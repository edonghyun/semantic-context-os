package io.scos.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 순위 품질 지표(테스트 지원): 질의마다 "기대 문서 중 하나가 처음 나오는 순위"로 recall@k와 MRR을 계산한다. 테스트 본문의 인라인 산술을 대신하며 자체 단위
 * 테스트({@code RankingMetricsTest})가 있다.
 */
public record RankingMetrics(double recallAtK, double mrr, List<String> misses) {
  /** 질의 하나의 결과(문서 id 순위 목록)와 기대 문서 집합. */
  public record Judged(String query, List<String> rankedDocumentIds, List<String> expected) {}

  public static RankingMetrics of(List<Judged> judged, int k) {
    if (judged.isEmpty()) throw new IllegalArgumentException("no judged queries");
    double hits = 0, reciprocal = 0;
    List<String> misses = new ArrayList<>();
    for (Judged j : judged) {
      Integer rank = firstRelevantRank(j.rankedDocumentIds(), j.expected());
      if (rank != null && rank <= k) hits++;
      if (rank != null) reciprocal += 1.0 / rank;
      if (rank == null || rank > k) misses.add(j.query() + "@" + rank);
    }
    return new RankingMetrics(
        hits / judged.size(), reciprocal / judged.size(), List.copyOf(misses));
  }

  /** 1부터 세는 순위. 기대 문서가 결과에 없으면 null. */
  static Integer firstRelevantRank(List<String> ranked, List<String> expected) {
    for (int i = 0; i < ranked.size(); i++) if (expected.contains(ranked.get(i))) return i + 1;
    return null;
  }
}
