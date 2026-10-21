package io.scos.harness.o2;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 두 순위 목록(문서 id, 1위부터)의 겹침 지표. 둘 다 비면 같은 것(1), 하나만 비면 다른 것(0)으로 본다.
 *
 * <ul>
 *   <li>Jaccard@k: 상위 k개 집합의 |교집합|/|합집합| — 순서를 보지 않는다. "같은 문서를 찾았는가".
 *   <li>RBO(rank-biased overlap, Webber·Moffat·Zobel 2010, 외삽형 RBO_ext): 깊이 d마다 접두 겹침 비율을 p^(d-1)로
 *       가중한다. 상위 순위 차이를 더 무겁게 보고, 한쪽에만 있는 문서(비결합 목록)를 다룬다 — Kendall τ·Spearman ρ는 두 목록이 같은 원소를 가져야
 *       해서 검색 결과 비교에 맞지 않는다. 깊이는 두 목록 중 짧은 쪽(최대 k)이다.
 * </ul>
 */
public final class RankingMetrics {
  /** RBO 가중(p): 0.9면 상위 10위가 가중의 약 86%를 차지한다(Webber 2010 표 1). */
  public static final double DEFAULT_RBO_P = 0.9;

  private RankingMetrics() {}

  public static double jaccard(List<String> a, List<String> b, int k) {
    Set<String> left = new HashSet<>(top(a, k));
    Set<String> right = new HashSet<>(top(b, k));
    if (left.isEmpty() && right.isEmpty()) return 1;
    Set<String> union = new HashSet<>(left);
    union.addAll(right);
    left.retainAll(right);
    return (double) left.size() / union.size();
  }

  /**
   * RBO_ext(Webber·Moffat·Zobel 2010 식 32, 길이가 다른 목록의 외삽). 두 목록을 k에서 자른 뒤 짧은 쪽 S(길이 s), 긴 쪽 L(길이
   * l)로 둔다. X_d는 S의 앞 min(d, s)개와 L의 앞 d개의 교집합 크기다.
   *
   * <pre>
   * RBO_ext = ((1−p)/p)·( Σ_{d=1..l} (X_d/d)·p^d + Σ_{d=s+1..l} (X_s·(d−s)/(s·d))·p^d ) + ((X_l − X_s)/l + X_s/s)·p^l
   * </pre>
   *
   * 길이가 같으면(s = l) 둘째 합이 사라지고 (X_k/k)·p^k + ((1−p)/p)·Σ (X_d/d)·p^d가 된다.
   */
  public static double rbo(List<String> a, List<String> b, int k, double p) {
    List<String> left = top(a, k);
    List<String> right = top(b, k);
    if (left.isEmpty() && right.isEmpty()) return 1;
    List<String> shorter = left.size() <= right.size() ? left : right;
    List<String> longer = shorter == left ? right : left;
    int s = shorter.size();
    int l = longer.size();
    if (s == 0) return 0;
    Set<String> seenShort = new HashSet<>();
    Set<String> seenLong = new HashSet<>();
    int overlap = 0;
    int overlapAtS = 0;
    double sum = 0;
    for (int d = 1; d <= l; d++) {
      String y = longer.get(d - 1);
      if (d <= s) {
        String x = shorter.get(d - 1);
        if (x.equals(y)) overlap++;
        else {
          if (seenLong.contains(x)) overlap++;
          if (seenShort.contains(y)) overlap++;
        }
        seenShort.add(x);
      } else if (seenShort.contains(y)) overlap++;
      seenLong.add(y);
      if (d == s) overlapAtS = overlap;
      sum += (double) overlap / d * Math.pow(p, d);
      if (d > s) sum += (double) overlapAtS * (d - s) / ((double) s * d) * Math.pow(p, d);
    }
    double tail = ((double) (overlap - overlapAtS) / l + (double) overlapAtS / s) * Math.pow(p, l);
    return (1 - p) / p * sum + tail;
  }

  /** 목록에서 id의 순위(1부터). 없으면 empty. */
  public static Optional<Integer> rankOf(List<String> ranked, String id) {
    int index = ranked.indexOf(id);
    return index < 0 ? Optional.empty() : Optional.of(index + 1);
  }

  /** 정답 문서 중 가장 먼저 나오는 것의 순위(1부터). 없으면 empty. */
  public static Optional<Integer> firstHitRank(List<String> ranked, List<String> expected) {
    for (int i = 0; i < ranked.size(); i++)
      if (expected.contains(ranked.get(i))) return Optional.of(i + 1);
    return Optional.empty();
  }

  static List<String> top(List<String> ranked, int k) {
    return ranked.subList(0, Math.min(k, ranked.size()));
  }
}
