package io.scos.harness.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 질의 평균 지표의 하한(--min-recall·--min-mrr·--min-ndcg). 평균이 하한보다 작을 때만 위반이다 — 하한과 같으면 통과한다. 주지 않은 하한은
 * 판정하지 않는다.
 */
public record EvaluationFloors(
    Optional<Double> recall, Optional<Double> mrr, Optional<Double> ndcg) {
  public boolean any() {
    return recall.isPresent() || mrr.isPresent() || ndcg.isPresent();
  }

  /** 위반한 지표 이름(recall·mrr·ndcg 순서). */
  public List<String> violations(double recall, double mrr, double ndcg) {
    List<String> out = new ArrayList<>();
    if (this.recall.filter(min -> recall < min).isPresent()) out.add("recall");
    if (this.mrr.filter(min -> mrr < min).isPresent()) out.add("mrr");
    if (this.ndcg.filter(min -> ndcg < min).isPresent()) out.add("ndcg");
    return List.copyOf(out);
  }
}
