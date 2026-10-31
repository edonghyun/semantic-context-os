package io.scos.engine.internal.search.refine;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import java.util.ArrayList;
import java.util.List;

/** 티어 우선: compacted를 앞에(점수 +1 밴드) — 랭커가 점수로 재정렬해도 순서가 보존된다. */
public final class TierPriority implements RefineStep {
  @Override
  public List<Candidate> apply(
      AccessScope access, SpaceId space, List<Candidate> in, RetrievalPlan plan) {
    if (!plan.settings().tierPriority()) return in;
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : in)
      if (c.unit().tier() == Tier.COMPACTED) out.add(new Candidate(c.unit(), c.baseScore() + 1));
    for (Candidate c : in) if (c.unit().tier() != Tier.COMPACTED) out.add(c);
    return out;
  }
}
