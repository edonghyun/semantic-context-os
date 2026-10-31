package io.scos.engine.internal.search.signal;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.spi.store.Tier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 점수 = 기본 × Π 계수 (+ compacted 동점 가산 기본·0.001) 뒤 점수 내림차순(안정 정렬). 날짜 가산이 꺼지면 {@link RecencySignal}과
 * 동점 가산을 건너뛰되 정렬은 한다.
 */
public final class Scorer {
  private static final double COMPACTED_TIE_BREAK = 0.001;

  private final List<Signal> signals;

  public Scorer(List<Signal> signals) {
    this.signals = List.copyOf(signals);
  }

  public List<Candidate> score(List<Candidate> in, RetrievalPlan plan, Instant now) {
    boolean recency = plan.recencyBoost();
    List<Candidate> out = new ArrayList<>(in.size());
    for (Candidate c : in) {
      double product = 1.0;
      for (Signal s : signals) {
        if (s instanceof RecencySignal && !recency) continue;
        product *= s.factor(c, now);
      }
      double tieBreak =
          recency && c.unit().tier() == Tier.COMPACTED ? c.baseScore() * COMPACTED_TIE_BREAK : 0;
      out.add(new Candidate(c.unit(), c.baseScore() * product + tieBreak));
    }
    out.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    return out;
  }
}
