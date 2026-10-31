package io.scos.engine.internal.search.refine;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import java.util.ArrayList;
import java.util.List;

/** 무효 유닛은 뺀다. archived는 점수에 archivedWeight를 곱한다. */
public final class ArchivePolicy implements RefineStep {
  @Override
  public List<Candidate> apply(
      AccessScope access, SpaceId space, List<Candidate> in, RetrievalPlan plan) {
    SearchSettings s = plan.settings();
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : in) {
      if (c.unit().isInvalidated()) continue;
      out.add(
          c.unit().tier() == Tier.ARCHIVED
              ? new Candidate(c.unit(), c.baseScore() * s.archivedWeight())
              : c);
    }
    return out;
  }
}
