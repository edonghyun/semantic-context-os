package io.scos.engine.internal.search.candidate;

import io.scos.engine.internal.access.CompactedUnits;
import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.SearchMetrics;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.plan.SearchSettings;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 후보 생성: 변형 질의 → 키워드‖벡터 → RRF → 유닛 로드. 키워드 채널과 임베딩 → 벡터는 {@link ParallelChannels}가 함께
 * 진행한다(DESIGN-PATTERNS §4). 키워드 점수 수식은 어댑터 소유다(ADR-0014).
 */
public final class CandidateSource {
  private final UnitStore units;
  private final KeywordIndex keywords;
  private final VectorChannel vectorChannel;
  private final SearchMetrics metrics;
  private final ParallelChannels channels;

  public CandidateSource(
      UnitStore units,
      KeywordIndex keywords,
      VectorChannel vectorChannel,
      SearchMetrics metrics,
      Executor lexicalExecutor) {
    this.units = units;
    this.keywords = keywords;
    this.vectorChannel = vectorChannel;
    this.metrics = metrics;
    this.channels = new ParallelChannels(lexicalExecutor);
  }

  /** recipe 태그 = search | assemble(지표 `scos.search.stage`). */
  public List<Candidate> collect(
      String recipe, AccessScope access, SpaceId space, String query, RetrievalPlan plan) {
    SearchFilter filter = new SearchFilter(true, null, access);
    SearchSettings s = plan.settings();
    QueryVariants variants = new QueryVariants(s.maxVariants());
    List<String> texts = new ArrayList<>();
    texts.add(query);
    texts.addAll(metrics.stage(recipe, "variants", () -> variants.of(query)));

    ParallelChannels.Hits hits =
        channels.run(
            () -> metrics.stage(recipe, "lexical", () -> lexicalHits(space, texts, s, filter)),
            () -> vectorChannel.hits(recipe, space, texts, s, filter));

    return metrics.stage(
        recipe, "load", () -> load(access, space, fuse(hits.lexical(), hits.vector(), s)));
  }

  private List<List<ScoredRef>> lexicalHits(
      SpaceId space, List<String> texts, SearchSettings s, SearchFilter filter) {
    List<List<ScoredRef>> out = new ArrayList<>();
    for (String t : texts) out.add(keywords.search(space, t, s.lexicalK(), filter));
    return out;
  }

  /** RRF: 채널 안은 리스트(원질의 1.0, 변형 0.9)마다 w/(k+rank+1)의 최댓값, 채널 사이는 합. Map 삽입 순서가 동점 순서다. */
  private Map<String, ScoredRef> fuse(
      List<List<ScoredRef>> lexical, List<List<ScoredRef>> vector, SearchSettings s) {
    Map<String, ScoredRef> merged = new LinkedHashMap<>();
    fuseChannel(merged, lexical, s.lexicalWeight(), s);
    fuseChannel(merged, vector, s.vectorWeight(), s);
    return merged;
  }

  private void fuseChannel(
      Map<String, ScoredRef> merged,
      List<List<ScoredRef>> lists,
      double channelWeight,
      SearchSettings s) {
    Map<String, ScoredRef> best = new LinkedHashMap<>();
    for (int i = 0; i < lists.size(); i++) {
      double weight = (i == 0 ? 1 : s.variantWeight()) * channelWeight;
      if (weight == 0) continue;
      List<ScoredRef> hits = lists.get(i);
      for (int rank = 0; rank < hits.size(); rank++) {
        ScoredRef hit = hits.get(rank);
        double score = Rrf.term(weight, s.rrfK(), rank);
        ScoredRef prev = best.get(hit.unitId());
        if (prev == null || score > prev.score())
          best.put(hit.unitId(), new ScoredRef(hit.unitId(), hit.documentId(), score));
      }
    }
    for (ScoredRef entry : best.values())
      merged.merge(
          entry.unitId(),
          entry,
          (a, b) -> new ScoredRef(a.unitId(), a.documentId(), a.score() + b.score()));
  }

  private List<Candidate> load(AccessScope access, SpaceId space, Map<String, ScoredRef> fused) {
    Map<String, ContextUnit> byId = new LinkedHashMap<>();
    for (ContextUnit u : units.getMany(space, access, new ArrayList<>(fused.keySet()))) {
      if (!CompactedUnits.admits(access, u)) continue;
      byId.put(u.id(), u);
    }
    List<Candidate> out = new ArrayList<>();
    for (ScoredRef f : fused.values()) {
      ContextUnit u = byId.get(f.unitId());
      if (u != null) out.add(new Candidate(u, f.score()));
    }
    return out;
  }
}
