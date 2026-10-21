package io.scos.engine.internal.search;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import io.scos.engine.support.text.Text;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import io.scos.spi.store.UnitStore;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * assemble_context(CONTRACTS C, TS ContextAssembler): 조립 레시피(QuestionSearch.assembleRecipe) → 날짜 가중
 * 랭커(DefaultRanker: 항상 1+0.1·0.99^days, compacted +0.1%) → 예산 탐욕 채우기(중복·invalidated 거부, 넘치면 건너뛰고
 * truncated) → 담긴 요약의 원본 드릴다운(질의 친화도 순, 점수 = 요약 − 1e-4) → 점수 내림차순 → U자 배치. MCP는 관측 모드라 회수 기록(heat)은
 * 없다(F16).
 */
public final class ContextAssembler {
  public static final int DEFAULT_BUDGET = 2000;
  public static final int MIN_BUDGET = 500;
  public static final int MAX_BUDGET = 8000;
  static final double DRILL_DOWN_SCORE_STEP = 0.0001;

  public record Item(ContextUnit unit, double rankScore) {}

  public record Bundle(List<Item> items, int usedTokens, boolean truncated) {}

  private final UnitStore units;
  private final QuestionSearch recipe;
  private final Clock clock;

  /** recipe는 search_context와 같은 검색 객체 — 조립 레시피의 단계 지표(recipe=assemble)도 그 지표 객체에 남는다. */
  public ContextAssembler(UnitStore units, QuestionSearch recipe, Clock clock) {
    this.units = units;
    this.recipe = recipe;
    this.clock = clock;
  }

  public Bundle assemble(AccessScope access, SpaceId space, String query, int budget) {
    List<Candidate> ranked =
        defaultRank(recipe.assembleRecipe(access, space, query), clock.instant());
    Builder builder = new Builder(budget);
    List<Candidate> compacted = new ArrayList<>();
    for (Candidate c : ranked) {
      if (builder.tryAdd(c.unit(), c.baseScore())
          && c.unit().tier() == Tier.COMPACTED
          && !c.unit().compactedFrom().isEmpty()) compacted.add(c);
    }
    drillDown(space, query, builder, compacted);
    return builder.build();
  }

  /** TS DefaultRanker: 날짜 가중은 항상, 동점이면 compacted를 점수 비례(0.1%)로 근소 우선. 날짜가 없거나 잘못된 유닛은 가산하지 않는다. */
  static List<Candidate> defaultRank(List<Candidate> candidates, Instant now) {
    List<Candidate> out = new ArrayList<>(candidates.size());
    for (Candidate c : candidates) {
      double freshness = 1;
      try {
        double days =
            Math.max(
                0,
                (now.toEpochMilli()
                        - Instant.parse(c.unit().provenance().capturedAt()).toEpochMilli())
                    / 86_400_000.0);
        freshness = SearchRanker.freshnessFactor(days);
      } catch (DateTimeParseException e) {
        // 가산 없음(TS와 같다)
      }
      double tieBreak = c.unit().tier() == Tier.COMPACTED ? c.baseScore() * 0.001 : 0;
      out.add(new Candidate(c.unit(), c.baseScore() * freshness + tieBreak));
    }
    out.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    return out;
  }

  /** 드릴다운(T15.2): 요약 점수 내림차순으로, 각 요약의 원본을 질의 친화도 내림차순(동률은 compactedFrom 순)으로 담는다. */
  private void drillDown(SpaceId space, String query, Builder builder, List<Candidate> compacted) {
    List<Candidate> byScore = new ArrayList<>(compacted);
    byScore.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    for (Candidate summary : byScore) {
      List<ContextUnit> originals = units.getMany(space, summary.unit().compactedFrom());
      List<Map.Entry<ContextUnit, Double>> scored = new ArrayList<>();
      for (ContextUnit o : originals) scored.add(Map.entry(o, affinity(query, o)));
      scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
      for (var e : scored) builder.tryAdd(e.getKey(), summary.baseScore() - DRILL_DOWN_SCORE_STEP);
    }
  }

  /** TS LexicalAffinity: 질의 토큰(2글자 이상) 포함 수 + 공백 무시 trigram 겹침 비율. 상대 순서만 쓴다. */
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

  /**
   * TS BundleBuilder + TokenBudget + FrontBackPlacement: 중복·invalidated 거부, 예산 초과 거부만 truncated, 점수
   * 내림차순 뒤 U자(짝수 앞·홀수 뒤 역순).
   */
  static final class Builder {
    private final int limit;
    private int used;
    private boolean skippedForBudget;
    private final Map<String, Double> scores = new LinkedHashMap<>();
    private final Map<String, ContextUnit> units = new LinkedHashMap<>();
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
      units.put(unit.id(), unit);
      scores.put(unit.id(), score);
      return true;
    }

    Bundle build() {
      List<Item> sorted = new ArrayList<>();
      for (String id : units.keySet()) sorted.add(new Item(units.get(id), scores.get(id)));
      sorted.sort((a, b) -> Double.compare(b.rankScore(), a.rankScore()));
      List<Item> fronts = new ArrayList<>();
      List<Item> backs = new ArrayList<>();
      for (int i = 0; i < sorted.size(); i++) (i % 2 == 0 ? fronts : backs).add(sorted.get(i));
      List<Item> arranged = new ArrayList<>(fronts);
      for (int i = backs.size() - 1; i >= 0; i--) arranged.add(backs.get(i));
      return new Bundle(List.copyOf(arranged), used, skippedForBudget);
    }
  }
}
