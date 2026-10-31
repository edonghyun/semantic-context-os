package io.scos.engine.internal.search;

import io.scos.engine.Effort;
import io.scos.engine.internal.access.CompactedUnits;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import io.scos.engine.internal.search.signal.Scorer;
import io.scos.engine.support.text.Text;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * assemble_context(CONTRACTS C): 조립 레시피(QuestionSearch.assembleRecipe) → 날짜 가중({@link Scorer}, 계획이
 * 켠 때만 — assemble 계획은 늘 켠다) → 예산 탐욕 채우기(중복·invalidated 거부, 넘치면 건너뛰고 truncated) → 담긴 요약의 원본 드릴다운(질의
 * 친화도 순, 점수 = 요약 − 1e-4) → 점수 내림차순 → U자 배치. MCP는 관측 모드라 회수 기록(heat)은 없다(F16). 제한 범위는 압축 후보를 제외하고 원본
 * 드릴다운도 하지 않는다.
 */
public final class ContextAssembler {
  static final double DRILL_DOWN_SCORE_STEP = 0.0001;

  public record Item(ContextUnit unit, double rankScore) {}

  public record Bundle(
      List<Item> items, int usedTokens, boolean truncated, Effort effort, boolean escalated) {}

  private final UnitStore units;
  private final QuestionSearch recipe;
  private final Clock clock;
  private final Scorer scorer;

  /** recipe는 search_context와 같은 검색 객체 — 조립 레시피의 단계 지표(recipe=assemble)도 그 지표 객체에 남는다. */
  public ContextAssembler(UnitStore units, QuestionSearch recipe, Clock clock, Scorer scorer) {
    this.units = units;
    this.recipe = recipe;
    this.clock = clock;
    this.scorer = scorer;
  }

  public Bundle assemble(
      AccessScope access, SpaceId space, String query, int budget, RetrievalPlan plan) {
    QuestionSearch.Recipe found = recipe.assembleEscalating(access, space, query, plan);
    List<Candidate> ranked = scorer.score(found.candidates(), plan, clock.instant());
    Builder builder = new Builder(budget);
    List<Candidate> compacted = new ArrayList<>();
    for (Candidate c : ranked) {
      if (builder.tryAdd(c.unit(), c.baseScore())
          && c.unit().tier() == Tier.COMPACTED
          && !c.unit().compactedFrom().isEmpty()) compacted.add(c);
    }
    drillDown(access, space, query, builder, compacted);
    return builder.build(found.effort(), found.escalated());
  }

  /** 드릴다운: 요약 점수 내림차순으로, 각 요약의 원본을 질의 친화도 내림차순(동률은 compactedFrom 순)으로 담는다. */
  private void drillDown(
      AccessScope access, SpaceId space, String query, Builder builder, List<Candidate> compacted) {
    if (!CompactedUnits.enabled(access)) return;
    List<Candidate> byScore = new ArrayList<>(compacted);
    byScore.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    for (Candidate summary : byScore) {
      List<ContextUnit> originals = units.getMany(space, access, summary.unit().compactedFrom());
      List<Map.Entry<ContextUnit, Double>> scored = new ArrayList<>();
      for (ContextUnit o : originals) scored.add(Map.entry(o, affinity(query, o)));
      scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
      for (var e : scored) builder.tryAdd(e.getKey(), summary.baseScore() - DRILL_DOWN_SCORE_STEP);
    }
  }

  /** 어휘 친화도: 질의 토큰(2글자 이상) 포함 수 + 공백 무시 trigram 겹침 비율. 상대 순서만 쓴다. */
  static double affinity(String query, ContextUnit unit) {
    String text = (unit.title() + "\n" + unit.content()).toLowerCase(Locale.ROOT);
    List<String> tokens =
        Text.splitWhitespace(query.toLowerCase(Locale.ROOT)).stream()
            .filter(t -> t.codePointCount(0, t.length()) >= 2)
            .toList();
    if (tokens.isEmpty()) return 0;
    long tokenHits = tokens.stream().filter(text::contains).count();
    String compact = Text.stripWhitespace(text);
    Set<String> trigrams = new java.util.LinkedHashSet<>();
    for (String t : tokens) trigrams.addAll(Text.trigrams(t));
    double trigramHits =
        trigrams.isEmpty()
            ? 0
            : trigrams.stream().filter(compact::contains).count() / (double) trigrams.size();
    return tokenHits + trigramHits;
  }

  /** 번들 조립(토큰 예산·앞뒤 배치): 중복·invalidated 거부, 예산 초과 거부만 truncated, 점수 내림차순 뒤 U자(짝수 앞·홀수 뒤 역순). */
  static final class Builder {
    private final int limit;
    private int used;
    private boolean skippedForBudget;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();

    Builder(int limit) {
      this.limit = limit;
    }

    boolean tryAdd(ContextUnit unit, double score) {
      if (seen.contains(unit.id()) || unit.isInvalidated()) return false;
      if (used + unit.tokens() > limit) {
        skippedForBudget = true;
        return false;
      }
      used += unit.tokens();
      seen.add(unit.id());
      items.put(unit.id(), new Item(unit, score));
      return true;
    }

    Bundle build(Effort effort, boolean escalated) {
      List<Item> sorted = new ArrayList<>(items.values());
      sorted.sort((a, b) -> Double.compare(b.rankScore(), a.rankScore()));
      List<Item> fronts = new ArrayList<>();
      List<Item> backs = new ArrayList<>();
      for (int i = 0; i < sorted.size(); i++) (i % 2 == 0 ? fronts : backs).add(sorted.get(i));
      List<Item> arranged = new ArrayList<>(fronts);
      for (int i = backs.size() - 1; i >= 0; i--) arranged.add(backs.get(i));
      return new Bundle(List.copyOf(arranged), used, skippedForBudget, effort, escalated);
    }
  }
}
