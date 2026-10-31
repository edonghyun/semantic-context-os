package io.scos.engine.internal.search.refine;

import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 문서당 상한: 문서별 앞에서 N개, compacted는 세지도 빼지도 않는다. */
public final class DocumentCap implements RefineStep {
  @Override
  public List<Candidate> apply(
      AccessScope access, SpaceId space, List<Candidate> in, RetrievalPlan plan) {
    SearchSettings s = plan.settings();
    if (s.perDocument() <= 0) return in;
    Map<String, Integer> seen = new LinkedHashMap<>();
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : in) {
      if (c.unit().tier() == Tier.COMPACTED) {
        out.add(c);
        continue;
      }
      int count = seen.merge(c.unit().documentId(), 1, Integer::sum);
      if (count <= s.perDocument()) out.add(c);
    }
    return out;
  }
}
