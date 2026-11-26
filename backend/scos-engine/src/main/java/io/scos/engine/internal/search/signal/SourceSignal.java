package io.scos.engine.internal.search.signal;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import java.time.Instant;

/** 원천 가산: 질의가 별명으로 말한 원천의 후보는 1 + weight, 나머지는 1.0(CONTRACTS B13). */
public final class SourceSignal implements Signal {
  private final double weight;

  public SourceSignal(double weight) {
    this.weight = weight;
  }

  @Override
  public double factor(Candidate c, RetrievalPlan plan, Instant now) {
    return plan.mentions().indexed().contains(c.unit().provenance().sourceId()) ? 1 + weight : 1.0;
  }
}
