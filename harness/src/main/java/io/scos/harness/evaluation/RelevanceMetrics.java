package io.scos.harness.evaluation;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 판정셋 한 질의의 이진 관련도 지표(판정셋 v1에는 등급이 없다). ranked는 서버가 낸 문서 id 순위(1위부터, 문서 단위로 중복을 뺀 것), relevant는 정답
 * 문서 id다 — 정답은 집합으로 보므로 같은 id가 여러 번 있어도 한 번만 센다. 정답에 코퍼스에 없는 id가 있으면 찾을 수 없으므로 recall을 낮춘다.
 *
 * <ul>
 *   <li>Recall@k = |top-k ∩ R| / |R|
 *   <li>역순위@k = top-k 안 첫 정답 순위의 역수, 없으면 0(질의 평균이 MRR@k)
 *   <li>nDCG@k = DCG / IDCG, DCG = Σ_{i≤k} rel_i / log2(i+1), IDCG = Σ_{i≤min(|R|,k)} 1 / log2(i+1)
 * </ul>
 *
 * 결과가 k보다 짧으면 있는 순위까지만 더한다(없는 순위는 관련 없음).
 */
public final class RelevanceMetrics {
  private RelevanceMetrics() {}

  public static double recallAt(List<String> ranked, Collection<String> relevant, int k) {
    Set<String> answers = answers(relevant);
    int found = 0;
    for (String id : top(ranked, k)) if (answers.contains(id)) found++;
    return (double) found / answers.size();
  }

  public static double reciprocalRankAt(List<String> ranked, Collection<String> relevant, int k) {
    Set<String> answers = answers(relevant);
    List<String> top = top(ranked, k);
    for (int i = 0; i < top.size(); i++) if (answers.contains(top.get(i))) return 1.0 / (i + 1);
    return 0;
  }

  public static double ndcgAt(List<String> ranked, Collection<String> relevant, int k) {
    Set<String> answers = answers(relevant);
    List<String> top = top(ranked, k);
    double dcg = 0;
    for (int i = 0; i < top.size(); i++) if (answers.contains(top.get(i))) dcg += gain(i + 1);
    double ideal = 0;
    for (int i = 1; i <= Math.min(answers.size(), k); i++) ideal += gain(i);
    return dcg / ideal;
  }

  /** 순위 i(1부터)의 할인된 이득 1 / log2(i+1). */
  private static double gain(int rank) {
    return Math.log(2) / Math.log(rank + 1);
  }

  private static Set<String> answers(Collection<String> relevant) {
    if (relevant.isEmpty()) throw new IllegalArgumentException("relevant ids must not be empty");
    return new HashSet<>(relevant);
  }

  private static List<String> top(List<String> ranked, int k) {
    if (k <= 0) throw new IllegalArgumentException("k must be positive");
    return ranked.subList(0, Math.min(k, ranked.size()));
  }
}
