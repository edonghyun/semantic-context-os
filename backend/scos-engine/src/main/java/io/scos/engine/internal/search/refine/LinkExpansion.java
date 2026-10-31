package io.scos.engine.internal.search.refine;

import io.scos.engine.internal.access.CompactedUnits;
import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.candidate.Rrf;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 링크 제3 리스트: 상위 parentWindow 후보가 링크한 문서마다 1/(k+부모랭크+shift+1), 문서당 최고 부모(DisMax). 기존 후보엔 합산하고, 링크로
 * 새로 들어온 유닛은 그 값으로 뒤에 붙인다.
 */
public final class LinkExpansion implements RefineStep {
  private final UnitStore units;

  public LinkExpansion(UnitStore units) {
    this.units = units;
  }

  @Override
  public List<Candidate> apply(
      AccessScope access, SpaceId space, List<Candidate> candidates, RetrievalPlan plan) {
    SearchSettings s = plan.settings();
    if (!s.linksEnabled() || candidates.isEmpty()) return candidates;
    List<Candidate> parents = parents(candidates, s);
    Contributions contributions = contributions(parents, s);
    if (contributions.isEmpty()) return candidates;
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : candidates) out.add(contributions.boost(c));
    List<UnitStore.LinkedUnit> refs =
        units.linkedUnits(space, access, parents.stream().map(c -> c.unit().id()).toList());
    List<ContextUnit> linked =
        units
            .getMany(space, access, refs.stream().map(UnitStore.LinkedUnit::unitId).toList())
            .stream()
            .filter(u -> CompactedUnits.admits(access, u))
            .toList();
    out.addAll(contributions.newcomers(candidates, linked));
    return out;
  }

  /** 점수 순 상위 parentWindow 후보. */
  private List<Candidate> parents(List<Candidate> candidates, SearchSettings s) {
    List<Candidate> ranked = new ArrayList<>(candidates);
    ranked.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    return ranked.size() > s.linkParentWindow() ? ranked.subList(0, s.linkParentWindow()) : ranked;
  }

  private Contributions contributions(List<Candidate> parents, SearchSettings s) {
    Map<String, Double> byDocument = new LinkedHashMap<>();
    for (int rank = 0; rank < parents.size(); rank++) {
      double contrib = Rrf.term(1.0, s.rrfK(), rank + s.linkRankShift());
      for (String doc : parents.get(rank).unit().links())
        if (byDocument.getOrDefault(doc, 0.0) < contrib) byDocument.put(doc, contrib);
    }
    return new Contributions(byDocument);
  }

  /** 링크된 문서마다 받는 점수. */
  private record Contributions(Map<String, Double> byDocument) {
    boolean isEmpty() {
      return byDocument.isEmpty();
    }

    /** 링크된 문서의 기존 후보는 그 점수를 더한다. */
    Candidate boost(Candidate c) {
      Double contrib = byDocument.get(c.unit().documentId());
      return contrib == null ? c : new Candidate(c.unit(), c.baseScore() + contrib);
    }

    /** 링크로 새로 들어온 유닛: 무효·archived·이미 있는 유닛과 점수 없는 문서는 뺀다. 같은 유닛은 한 번. */
    List<Candidate> newcomers(List<Candidate> candidates, List<ContextUnit> linked) {
      Set<String> present = new HashSet<>();
      for (Candidate c : candidates) present.add(c.unit().id());
      List<Candidate> out = new ArrayList<>();
      for (ContextUnit u : linked) {
        if (u.isInvalidated() || present.contains(u.id()) || u.tier() == Tier.ARCHIVED) continue;
        Double contrib = byDocument.get(u.documentId());
        if (contrib == null) continue;
        present.add(u.id());
        out.add(new Candidate(u, contrib));
      }
      return out;
    }
  }
}
