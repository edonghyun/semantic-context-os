package io.scos.engine.internal.search;

import io.scos.engine.Effort;
import io.scos.engine.Freshness;
import io.scos.engine.internal.search.candidate.CandidateSource;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.refine.RefineStep;
import io.scos.engine.internal.search.rerank.RerankOutcome;
import io.scos.engine.internal.search.rerank.Reranker;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * search_context(CONTRACTS B). 임베더는 필수이고 실패는 전파된다(B11, 저하 정책 없음). 후보(변형 → 키워드·벡터 → RRF → 로드) →
 * 다듬기(archived → 링크 → 정렬 → 문서당 상한) → 순서(티어 우선 → 재정렬 없음 → 점수) → 최신성. 단계 사이 상태는 지역 값이며 키워드 점수 수식은 어댑터
 * 소유다(ADR-0014). 키워드 채널과 임베딩 → 벡터는 {@link CandidateSource}가 함께 진행한다(DESIGN-PATTERNS §4).
 */
public final class QuestionSearch {
  /** access는 후보·정제·최신성 출구까지 전달한 접근 범위다(다중 사용자 접근 설계 §4). */
  public record Result(
      AccessScope access,
      List<Candidate> candidates,
      Freshness freshness,
      RerankOutcome rerank,
      Effort effort,
      boolean escalated) {
    Result asEscalated() {
      return new Result(access, candidates, freshness, rerank, effort, true);
    }
  }

  /** 조립 레시피 결과와 실제로 쓴 강도·상향 여부. */
  public record Recipe(List<Candidate> candidates, Effort effort, boolean escalated) {}

  private final CandidateSource source;
  private final RefineStep archive;
  private final RefineStep links;
  private final RefineStep documentCap;
  private final RefineStep tierPriority;
  private final Reranker reranker;
  private final Clock clock;
  private final SearchMetrics metrics;
  private final FreshnessReader freshness;
  private final Scorer scorer;
  private final Escalation escalation;

  public QuestionSearch(
      CandidateSource source,
      RefineStep archive,
      RefineStep links,
      RefineStep documentCap,
      RefineStep tierPriority,
      Reranker reranker,
      Clock clock,
      SearchMetrics metrics,
      FreshnessReader freshness,
      Scorer scorer,
      Escalation escalation) {
    this.source = source;
    this.archive = archive;
    this.links = links;
    this.documentCap = documentCap;
    this.tierPriority = tierPriority;
    this.reranker = reranker;
    this.clock = clock;
    this.metrics = metrics;
    this.freshness = freshness;
    this.scorer = scorer;
    this.escalation = escalation;
  }

  /** search_context: 한 번 실행하고, LOW에서 limit개에 못 미치면 MEDIUM으로 한 번 더 실행한다. */
  public Result search(
      AccessScope access, SpaceId space, String query, RetrievalPlan plan, int limit) {
    Result first = run(access, space, query, plan);
    return escalation
        .replan(plan, first.candidates().size(), limit)
        .map(up -> run(access, space, query, up).asEscalated())
        .orElse(first);
  }

  /** 조립 레시피를 실행하고, 후보가 0건이면 LOW를 MEDIUM으로 한 번 다시 실행한다. */
  Recipe assembleEscalating(AccessScope access, SpaceId space, String query, RetrievalPlan plan) {
    List<Candidate> first = assembleRecipe(access, space, query, plan);
    return escalation
        .replan(plan, first.size(), 1)
        .map(up -> new Recipe(assembleRecipe(access, space, query, up), up.effort(), true))
        .orElse(new Recipe(first, plan.effort(), false));
  }

  /** 조립 레시피 → 재정렬(상위 topK, 폴백은 결과로 보고) → 점수 → 최신성. */
  private Result run(AccessScope access, SpaceId space, String query, RetrievalPlan plan) {
    List<Candidate> recipe = assembleRecipe("search", access, space, query, plan);
    Reranker.Result reranked =
        plan.rerank()
            ? metrics.stage("search", "rerank", () -> reranker.rerank(query, recipe))
            : new Reranker.Result(recipe, new RerankOutcome.Skipped("low effort"));
    metrics.rerank(reranked.outcome());
    List<Candidate> candidates =
        metrics.stage(
            "search", "rank", () -> scorer.score(reranked.candidates(), plan, clock.instant()));
    Freshness fresh = metrics.stage("search", "freshness", () -> freshness.read(access, space));
    return new Result(
        access, List.copyOf(candidates), fresh, reranked.outcome(), plan.effort(), false);
  }

  /** 후보(변형 → 키워드·벡터 → RRF) → 다듬기 → 티어 우선까지(재정렬·점수 없음). assemble_context가 쓴다. */
  List<Candidate> assembleRecipe(
      AccessScope access, SpaceId space, String query, RetrievalPlan plan) {
    return assembleRecipe("assemble", access, space, query, plan);
  }

  /** recipe 태그 = search | assemble(지표 `scos.search.stage`). */
  List<Candidate> assembleRecipe(
      String recipe, AccessScope access, SpaceId space, String query, RetrievalPlan plan) {
    Objects.requireNonNull(access, "access");
    List<Candidate> loaded = source.collect(recipe, access, space, query, plan);
    List<Candidate> linked =
        metrics.stage(
            recipe,
            "links",
            () -> links.apply(access, space, archive.apply(access, space, loaded, plan), plan));
    List<Candidate> candidates = new ArrayList<>(linked);
    candidates.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    candidates = documentCap.apply(access, space, candidates, plan);
    return tierPriority.apply(access, space, candidates, plan);
  }
}
