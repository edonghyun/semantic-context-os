package io.scos.engine.internal.search;

import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.internal.search.candidate.Rrf;
import io.scos.spi.store.AccessScope;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * related_documents(CONTRACTS E): 시드(앞 5유닛) → 임베딩 채널(시드 벡터 일괄 검색 k=50, 자기 문서 제외, 문서별 최고 유사도,
 * archived 제외) ∪ 링크 채널(시드의 1홉 링크 대상 문서 수, 최댓값 정규화) → 채널별 순위(점수 내림차순, 동점 documentId 사전순) → RRF
 * 1/(60+rank+1) 합산, via = 기여 채널 집합 → 점수 내림차순·documentId 사전순 → limit.
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

  /** JS localeCompare(ICU 기본 로케일)의 대응 — 루트 로케일 Collator(F-5 기록: 아스트랄·대소문자 차이 가능). */
  static final Comparator<String> LOCALE = Collator.getInstance(Locale.ROOT)::compare;

  private final UnitStore units;
  private final @Nullable VectorIndex vectors;

  public RelatedDocuments(UnitStore units, VectorIndex vectors) {
    this.units = units;
    this.vectors = vectors;
  }

  /** 벡터 포트 없이 링크 채널만으로 관련 문서를 찾는다. */
  public RelatedDocuments(UnitStore units) {
    this.units = units;
    this.vectors = null;
  }

  public List<Related> find(AccessScope access, SpaceId space, String documentId, int limit) {
    List<ContextUnit> seeds =
        units.listByDocument(space, access, documentId, new UnitStore.Range(0, SEED_LIMIT));
    if (seeds.isEmpty()) throw new DocumentNotFoundException(documentId);

    Map<String, Double> embeddingScores = embeddingChannel(access, space, documentId, seeds);
    Map<String, Double> linkScores = linkChannel(access, space, documentId, seeds);

    Map<String, RelatedCandidate> candidates = new LinkedHashMap<>();
    contribute(embeddingScores, Via.EMBEDDING, candidates);
    contribute(linkScores, Via.LINK, candidates);

    List<RelatedCandidate> merged = new ArrayList<>(candidates.values());
    merged.sort(ranking(RelatedCandidate::score, RelatedCandidate::documentId));
    List<RelatedCandidate> top = merged.subList(0, Math.min(limit, merged.size()));

    Map<String, UnitStore.DocumentInfo> infos = new LinkedHashMap<>();
    for (UnitStore.DocumentInfo d :
        units.getDocuments(space, access, top.stream().map(RelatedCandidate::documentId).toList()))
      infos.put(d.documentId(), d);
    List<Related> out = new ArrayList<>();
    for (RelatedCandidate c : top) {
      Optional<UnitStore.DocumentInfo> info = Optional.ofNullable(infos.get(c.documentId()));
      out.add(
          new Related(
              c.documentId(),
              info.map(UnitStore.DocumentInfo::title).orElse(""),
              info.map(UnitStore.DocumentInfo::uri).orElse(""),
              c.score(),
              c.via()));
    }
    return out;
  }

  private Map<String, Double> embeddingChannel(
      AccessScope access, SpaceId space, String self, List<ContextUnit> seeds) {
    VectorIndex index = vectors;
    if (index == null) return Map.of();
    Map<String, double[]> byId =
        index.vectorsOf(space, access, seeds.stream().map(ContextUnit::id).toList());
    List<double[]> queries =
        seeds.stream().map(u -> byId.get(u.id())).filter(v -> v != null).toList();
    Map<String, Double> scores = new LinkedHashMap<>();
    if (queries.isEmpty()) return scores;
    for (List<ScoredRef> hits :
        index.search(space, queries, CANDIDATE_LIMIT, new SearchFilter(false, null, access))) {
      for (ScoredRef hit : hits) {
        if (hit.documentId().equals(self)) continue;
        scores.merge(hit.documentId(), hit.score(), Math::max);
      }
    }
    return scores;
  }

  private Map<String, Double> linkChannel(
      AccessScope access, SpaceId space, String self, List<ContextUnit> seeds) {
    Map<String, Double> counts = new LinkedHashMap<>();
    for (UnitStore.LinkedUnit ref :
        units.linkedUnits(space, access, seeds.stream().map(ContextUnit::id).toList())) {
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
      Map<String, Double> channel, Via which, Map<String, RelatedCandidate> candidates) {
    List<Map.Entry<String, Double>> ranked = new ArrayList<>(channel.entrySet());
    ranked.sort(ranking(Map.Entry::getValue, Map.Entry::getKey));
    for (int rank = 0; rank < ranked.size(); rank++) {
      String doc = ranked.get(rank).getKey();
      candidates.computeIfAbsent(doc, RelatedCandidate::new).add(Rrf.term(1.0, RRF_K, rank), which);
    }
  }

  /** 점수 내림차순, 동점은 documentId 사전순(채널 안 순위와 최종 순위가 같은 규칙이다). */
  private static <T> Comparator<T> ranking(
      ToDoubleFunction<T> score, Function<T, String> documentId) {
    return Comparator.comparingDouble(score).reversed().thenComparing(documentId, LOCALE);
  }

  /** 관련 문서 후보 하나: 채널마다 더한 RRF 점수와 기여한 채널. */
  private static final class RelatedCandidate {
    private final String documentId;
    private final EnumSet<Via> channels = EnumSet.noneOf(Via.class);
    private double score;

    RelatedCandidate(String documentId) {
      this.documentId = documentId;
    }

    void add(double term, Via channel) {
      score += term;
      channels.add(channel);
    }

    String documentId() {
      return documentId;
    }

    double score() {
      return score;
    }

    Via via() {
      return channels.size() == 2 ? Via.BOTH : channels.iterator().next();
    }
  }
}
