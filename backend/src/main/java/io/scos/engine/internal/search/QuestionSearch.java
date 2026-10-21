package io.scos.engine.internal.search;

import io.scos.engine.Freshness;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * search_context(CONTRACTS B, TS QuestionSearch 레시피를 합성 메서드로). 임베더는 필수이고 실패는 전파된다(B11, 저하 정책 없음).
 * 후보(변형 → 키워드·벡터 → RRF) → 다듬기(로드 → archived → 링크 → 정렬 → 문서당 상한) → 순서(티어 우선 → 재정렬 없음 → 랭커) → 최신성. 단계
 * 사이 상태는 지역 값이며 키워드 점수 수식은 어댑터 소유다(ADR-0014). 키워드 채널은 전용 실행기에서, 임베딩 → 벡터는 호출 스레드에서 함께 진행한다(TS
 * recipe의 parallel, DESIGN-PATTERNS §4, F3 T3.3). 두 쪽이 다 끝난 뒤 합치므로 순서·동점은 순차 실행과 같다.
 */
public final class QuestionSearch {
  public record Candidate(ContextUnit unit, double baseScore) {}

  /** access는 레시피가 받은 접근 범위다(0.1.0은 필터 없이 전달만 한다, 다중 사용자 접근 설계 §8). */
  public record Result(
      AccessScope access, List<Candidate> candidates, Freshness freshness, RerankOutcome rerank) {}

  /** 후보 필터. 접근 키({@link AccessScope#accessKeys()})가 0.2-a에서 여기에 합쳐진다 — 0.1.0은 비어 있어 합치지 않는다. */
  private static final SearchFilter CANDIDATES = new SearchFilter(true, null);

  private final UnitStore units;
  private final KeywordIndex keywords;
  private final VectorIndex vectors;
  private final Embedder embedder;
  private final SearchSettings s;
  private final Clock clock;
  private final QueryVariants variants;
  private final SearchRanker ranker = new SearchRanker();
  private final FreshnessReader freshness;
  private final Reranker reranker;
  private final SearchMetrics metrics;
  private final Executor lexicalExecutor;

  public QuestionSearch(
      UnitStore units,
      KeywordIndex keywords,
      VectorIndex vectors,
      Embedder embedder,
      Reranker reranker,
      SearchSettings settings,
      Clock clock,
      SearchMetrics metrics,
      Executor lexicalExecutor) {
    this.metrics = metrics;
    this.lexicalExecutor = lexicalExecutor;
    this.reranker = reranker;
    this.units = units;
    this.keywords = keywords;
    this.vectors = vectors;
    this.embedder = embedder;
    this.s = settings;
    this.clock = clock;
    this.variants = new QueryVariants(settings.maxVariants());
    this.freshness = new FreshnessReader(units, clock);
  }

  /** search_context: 조립 레시피 → 재정렬(상위 topK, 폴백은 결과로 보고) → 랭커 → 최신성. */
  public Result search(AccessScope access, SpaceId space, String query) {
    List<Candidate> recipe = assembleRecipe("search", access, space, query);
    Reranker.Result reranked =
        metrics.stage("search", "rerank", () -> reranker.rerank(query, recipe));
    metrics.rerank(reranked.outcome());
    List<Candidate> candidates =
        metrics.stage(
            "search", "rank", () -> ranker.rank(query, reranked.candidates(), clock.instant()));
    Freshness fresh = metrics.stage("search", "freshness", () -> freshness.read(space));
    return new Result(access, List.copyOf(candidates), fresh, reranked.outcome());
  }

  /**
   * TS `QuestionSearch.assembleRecipe`: 후보(변형 → 키워드·벡터 → RRF) → 다듬기 → 티어 우선까지(재정렬·랭커 없음).
   * assemble_context가 쓴다.
   */
  List<Candidate> assembleRecipe(AccessScope access, SpaceId space, String query) {
    return assembleRecipe("assemble", access, space, query);
  }

  /** recipe 태그 = search | assemble(지표 `scos.search.stage`, TS StageTrace 단계 이름과 대응). */
  List<Candidate> assembleRecipe(String recipe, AccessScope access, SpaceId space, String query) {
    Objects.requireNonNull(access, "access");
    List<String> texts = new ArrayList<>();
    texts.add(query);
    texts.addAll(metrics.stage(recipe, "variants", () -> variants.of(query)));

    CompletableFuture<List<List<ScoredRef>>> lexicalJob =
        CompletableFuture.supplyAsync(
            () -> metrics.stage(recipe, "lexical", () -> lexicalHits(space, texts)),
            lexicalExecutor);
    List<List<ScoredRef>> vector;
    try {
      throwIfFailed(lexicalJob); // 순차처럼: 키워드가 이미 실패했으면 임베더(유료 API)를 부르지 않는다
      List<double[]> queryVectors = metrics.stage(recipe, "embed", () -> embedder.embed(texts));
      vector = metrics.stage(recipe, "vector", () -> vectorHits(space, queryVectors));
    } catch (RuntimeException | Error failure) {
      throw unchecked(keywordFailureFirst(lexicalJob, failure));
    }
    List<List<ScoredRef>> lexical = join(lexicalJob);

    List<Candidate> loaded =
        metrics.stage(recipe, "load", () -> load(space, fuse(lexical, vector)));
    List<Candidate> linked =
        metrics.stage(recipe, "links", () -> linkExpansion(space, archivePolicy(loaded)));
    List<Candidate> candidates = new ArrayList<>(linked);
    candidates.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    candidates = documentCap(candidates);
    return tierPriority(candidates);
  }

  private List<List<ScoredRef>> lexicalHits(SpaceId space, List<String> texts) {
    List<List<ScoredRef>> out = new ArrayList<>();
    for (String t : texts) out.add(keywords.search(space, t, s.lexicalK(), CANDIDATES));
    return out;
  }

  /**
   * 키워드 채널의 결과. 실패는 감싸지 않고 원래 예외로 던진다. 한쪽만 실패하면 호출자가 보는 예외는 순차 실행과 같고, 둘 다 실패해도 순차처럼 키워드 예외가
   * 이긴다({@link #keywordFailureFirst}).
   */
  private static <T> T join(CompletableFuture<T> job) {
    try {
      return job.join();
    } catch (CompletionException e) {
      throw unchecked(e.getCause() == null ? e : e.getCause());
    }
  }

  private static void throwIfFailed(CompletableFuture<?> job) {
    if (job.isCompletedExceptionally()) join(job);
  }

  /**
   * 임베딩·벡터 쪽이 실패하면 키워드 채널이 끝나기를 기다린 뒤 던질 예외를 고른다 — 응답 뒤에 이 요청의 질의가 연결을 쥐고 남지 않는다. 키워드도 실패했으면 순차
   * 실행처럼 키워드 예외가 이기고 임베딩·벡터 예외는 덧붙인다(suppressed). 이미 진행 중인 임베더 호출은 취소하지 못한다(Embedder 포트에 취소가 없다) —
   * 그 호출이 끝날 때까지 실패 응답이 늦는다.
   */
  @SuppressWarnings("ReferenceEquality") // 같은 예외 객체인지 본다: throwIfFailed가 다시 던진 키워드 예외
  private static Throwable keywordFailureFirst(CompletableFuture<?> job, Throwable failure) {
    Throwable keyword =
        job.handle(
                (ignored, error) ->
                    error instanceof CompletionException c && c.getCause() != null
                        ? c.getCause()
                        : error)
            .join();
    if (keyword == null || keyword == failure) return failure;
    keyword.addSuppressed(failure);
    return keyword;
  }

  /** 런타임 예외는 그대로 돌려주고 Error는 그대로 던진다. 그 밖(포트는 checked 예외를 선언하지 않는다)은 감싼다. */
  private static RuntimeException unchecked(Throwable t) {
    if (t instanceof Error e) throw e;
    return t instanceof RuntimeException r ? r : new CompletionException(t);
  }

  private List<List<ScoredRef>> vectorHits(SpaceId space, List<double[]> queryVectors) {
    List<List<ScoredRef>> out = new ArrayList<>();
    for (List<ScoredRef> hits : vectors.search(space, queryVectors, s.vectorK(), CANDIDATES)) {
      out.add(
          s.vectorMinScore() <= 0
              ? hits
              : hits.stream().filter(h -> h.score() >= s.vectorMinScore()).toList());
    }
    return out;
  }

  /** RRF: 채널 안은 리스트(원질의 1.0, 변형 0.9)마다 w/(k+rank+1)의 최댓값, 채널 사이는 합. Map 삽입 순서가 동점 순서다. */
  private Map<String, ScoredRef> fuse(List<List<ScoredRef>> lexical, List<List<ScoredRef>> vector) {
    Map<String, ScoredRef> merged = new LinkedHashMap<>();
    fuseChannel(merged, lexical, s.lexicalWeight());
    fuseChannel(merged, vector, s.vectorWeight());
    return merged;
  }

  private void fuseChannel(
      Map<String, ScoredRef> merged, List<List<ScoredRef>> lists, double channelWeight) {
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

  private List<Candidate> load(SpaceId space, Map<String, ScoredRef> fused) {
    Map<String, ContextUnit> byId = new LinkedHashMap<>();
    for (ContextUnit u : units.getMany(space, new ArrayList<>(fused.keySet()))) byId.put(u.id(), u);
    List<Candidate> out = new ArrayList<>();
    for (ScoredRef f : fused.values()) {
      ContextUnit u = byId.get(f.unitId());
      if (u != null) out.add(new Candidate(u, f.score()));
    }
    return out;
  }

  private List<Candidate> archivePolicy(List<Candidate> candidates) {
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.unit().isInvalidated()) continue;
      out.add(
          c.unit().tier() == Tier.ARCHIVED
              ? new Candidate(c.unit(), c.baseScore() * s.archivedWeight())
              : c);
    }
    return out;
  }

  /**
   * 링크 제3 리스트(T12.2): 상위 parentWindow 후보의 링크 문서에 1/(k+부모랭크+shift+1), 문서당 최고 부모(DisMax). 기존 후보엔 합산,
   * 새 유닛은 그 값으로 뒤에.
   */
  private List<Candidate> linkExpansion(SpaceId space, List<Candidate> candidates) {
    if (!s.linksEnabled() || candidates.isEmpty()) return candidates;
    List<Candidate> ranked = new ArrayList<>(candidates);
    ranked.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    if (ranked.size() > s.linkParentWindow()) ranked = ranked.subList(0, s.linkParentWindow());
    Map<String, Double> contribByDoc = new LinkedHashMap<>();
    for (int rank = 0; rank < ranked.size(); rank++) {
      double contrib = Rrf.term(1.0, s.rrfK(), rank + s.linkRankShift());
      for (String doc : ranked.get(rank).unit().links())
        if (contribByDoc.getOrDefault(doc, 0.0) < contrib) contribByDoc.put(doc, contrib);
    }
    if (contribByDoc.isEmpty()) return candidates;
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : candidates) {
      Double contrib = contribByDoc.get(c.unit().documentId());
      out.add(contrib == null ? c : new Candidate(c.unit(), c.baseScore() + contrib));
    }
    Set<String> present = new HashSet<>();
    for (Candidate c : candidates) present.add(c.unit().id());
    List<UnitStore.LinkedUnit> refs =
        units.linkedUnits(space, ranked.stream().map(c -> c.unit().id()).toList());
    for (ContextUnit u :
        units.getMany(space, refs.stream().map(UnitStore.LinkedUnit::unitId).toList())) {
      if (u.isInvalidated() || present.contains(u.id()) || u.tier() == Tier.ARCHIVED) continue;
      Double contrib = contribByDoc.get(u.documentId());
      if (contrib == null) continue;
      present.add(u.id());
      out.add(new Candidate(u, contrib));
    }
    return out;
  }

  /** 문서당 상한(T19.3): 문서별 앞에서 N개, compacted는 세지도 빼지도 않는다. */
  private List<Candidate> documentCap(List<Candidate> candidates) {
    if (s.perDocument() <= 0) return candidates;
    Map<String, Integer> seen = new LinkedHashMap<>();
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.unit().tier() == Tier.COMPACTED) {
        out.add(c);
        continue;
      }
      int count = seen.merge(c.unit().documentId(), 1, Integer::sum);
      if (count <= s.perDocument()) out.add(c);
    }
    return out;
  }

  /** 티어 우선: compacted를 앞에(점수 +1 밴드) — 랭커가 점수로 재정렬해도 순서가 보존된다. */
  private List<Candidate> tierPriority(List<Candidate> candidates) {
    if (!s.tierPriority()) return candidates;
    List<Candidate> out = new ArrayList<>();
    for (Candidate c : candidates)
      if (c.unit().tier() == Tier.COMPACTED) out.add(new Candidate(c.unit(), c.baseScore() + 1));
    for (Candidate c : candidates) if (c.unit().tier() != Tier.COMPACTED) out.add(c);
    return out;
  }
}
