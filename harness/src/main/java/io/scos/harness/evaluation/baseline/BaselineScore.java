package io.scos.harness.evaluation.baseline;

import io.scos.harness.evaluation.RelevanceMetrics;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기준선 점수 하나(질의 하나). 기준선에 유리하게 센다 — recall·nDCG는 rg 상위 k와 git 상위 k의 합집합(rg 먼저, 중복 한 번)을 한 목록으로 보고,
 * 역순위는 두 목록 역순위의 최댓값이다.
 */
public record BaselineScore(double recall, double reciprocalRank, double ndcg) {

  public static BaselineScore of(List<String> rg, List<String> git, Set<String> expected, int k) {
    Set<String> union = new LinkedHashSet<>(top(rg, k));
    union.addAll(top(git, k));
    // 합집합 전체를 본다: 깊이를 목록 길이까지 넓힌다
    int depth = Math.max(k, union.size());
    List<String> merged = List.copyOf(union);
    return new BaselineScore(
        RelevanceMetrics.recallAt(merged, expected, depth),
        Math.max(
            RelevanceMetrics.reciprocalRankAt(rg, expected, k),
            RelevanceMetrics.reciprocalRankAt(git, expected, k)),
        RelevanceMetrics.ndcgAt(merged, expected, depth));
  }

  /** 한 출처만의 점수(bySource용): 상위 k 그대로. */
  public static BaselineScore single(List<String> ranked, Set<String> expected, int k) {
    return new BaselineScore(
        RelevanceMetrics.recallAt(ranked, expected, k),
        RelevanceMetrics.reciprocalRankAt(ranked, expected, k),
        RelevanceMetrics.ndcgAt(ranked, expected, k));
  }

  private static List<String> top(List<String> ranked, int k) {
    return ranked.subList(0, Math.min(k, ranked.size()));
  }
}
