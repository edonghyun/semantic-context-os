package io.scos.engine.internal.search;

import io.scos.engine.DocumentNotFoundException;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * related_documents(CONTRACTS E, TS DocumentNeighborsSearch 레시피): 시드(앞 5유닛) → 임베딩 채널(시드 벡터 일괄 검색
 * k=50, 자기 문서 제외, 문서별 최고 유사도, archived 제외) ∪ 링크 채널(시드의 1홉 링크 대상 문서 수, 최댓값 정규화) → 채널별 순위(점수 내림차순, 동점
 * documentId 사전순) → RRF 1/(60+rank+1) 합산, via = 기여 채널 집합 → 점수 내림차순·documentId 사전순 → limit.
 */
public final class RelatedDocuments {
  public enum Via {
    EMBEDDING,
    LINK,
    BOTH
  }

  public record Related(String documentId, String title, String uri, double score, Via via) {}

  static final int SEED_LIMIT = 5;
  static final int CANDIDATE_LIMIT = 50;
  static final int RRF_K = 60;

  /** TS localeCompare(ICU 기본 로케일)의 대응 — 루트 로케일 Collator(F-5 기록: 아스트랄·대소문자 차이 가능). */
  static final Comparator<String> LOCALE = Collator.getInstance(Locale.ROOT)::compare;

  private final UnitStore units;
  private final VectorIndex vectors;

  public RelatedDocuments(UnitStore units, VectorIndex vectors) {
    this.units = units;
    this.vectors = vectors;
  }

  public List<Related> find(SpaceId space, String documentId, int limit) {
    List<ContextUnit> seeds =
        units.listByDocument(space, documentId, new UnitStore.Range(0, SEED_LIMIT));
    if (seeds.isEmpty()) throw new DocumentNotFoundException(documentId);

    Map<String, Double> embeddingScores = embeddingChannel(space, documentId, seeds);
    Map<String, Double> linkScores = linkChannel(space, documentId, seeds);

    Map<String, double[]> score = new LinkedHashMap<>();
    Map<String, EnumSet<Via>> via = new LinkedHashMap<>();
    contribute(embeddingScores, Via.EMBEDDING, score, via);
    contribute(linkScores, Via.LINK, score, via);

    List<Map.Entry<String, double[]>> merged = new ArrayList<>(score.entrySet());
    merged.sort(
        (a, b) -> {
          int c = Double.compare(b.getValue()[0], a.getValue()[0]);
          return c != 0 ? c : LOCALE.compare(a.getKey(), b.getKey());
        });
    List<Map.Entry<String, double[]>> top = merged.subList(0, Math.min(limit, merged.size()));

    Map<String, UnitStore.DocumentInfo> infos = new LinkedHashMap<>();
    for (UnitStore.DocumentInfo d :
        units.getDocuments(space, top.stream().map(Map.Entry::getKey).toList()))
      infos.put(d.documentId(), d);
    List<Related> out = new ArrayList<>();
    for (var e : top) {
      EnumSet<Via> v = via.get(e.getKey());
      UnitStore.DocumentInfo info = infos.get(e.getKey());
      out.add(
          new Related(
              e.getKey(),
              info == null ? "" : info.title(),
              info == null ? "" : info.uri(),
              e.getValue()[0],
              v.size() == 2 ? Via.BOTH : v.iterator().next()));
    }
    return out;
  }

  private Map<String, Double> embeddingChannel(
      SpaceId space, String self, List<ContextUnit> seeds) {
    Map<String, double[]> byId =
        vectors.vectorsOf(space, seeds.stream().map(ContextUnit::id).toList());
    List<double[]> queries =
        seeds.stream().map(u -> byId.get(u.id())).filter(v -> v != null).toList();
    Map<String, Double> scores = new LinkedHashMap<>();
    if (queries.isEmpty()) return scores;
    for (List<ScoredRef> hits :
        vectors.search(space, queries, CANDIDATE_LIMIT, SearchFilter.NONE)) {
      for (ScoredRef hit : hits) {
        if (hit.documentId().equals(self)) continue;
        scores.merge(hit.documentId(), hit.score(), Math::max);
      }
    }
    return scores;
  }

  private Map<String, Double> linkChannel(SpaceId space, String self, List<ContextUnit> seeds) {
    Map<String, Double> counts = new LinkedHashMap<>();
    for (UnitStore.LinkedUnit ref :
        units.linkedUnits(space, seeds.stream().map(ContextUnit::id).toList())) {
      if (ref.documentId().equals(self)) continue;
      counts.merge(ref.documentId(), 1.0, Double::sum);
    }
    if (counts.isEmpty()) return counts;
    double max = counts.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
    counts.replaceAll((k, v) -> v / max);
    return counts;
  }

  /** 채널 내 순위(점수 내림차순, 동점 documentId 사전순)를 RRF로 더한다. */
  private static void contribute(
      Map<String, Double> channel,
      Via which,
      Map<String, double[]> score,
      Map<String, EnumSet<Via>> via) {
    List<Map.Entry<String, Double>> ranked = new ArrayList<>(channel.entrySet());
    ranked.sort(
        (a, b) -> {
          int c = Double.compare(b.getValue(), a.getValue());
          return c != 0 ? c : LOCALE.compare(a.getKey(), b.getKey());
        });
    for (int rank = 0; rank < ranked.size(); rank++) {
      String doc = ranked.get(rank).getKey();
      score.computeIfAbsent(doc, d -> new double[1])[0] += Rrf.term(1.0, RRF_K, rank);
      via.computeIfAbsent(doc, d -> EnumSet.noneOf(Via.class)).add(which);
    }
  }
}
