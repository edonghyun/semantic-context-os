package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.ScoredRef;
import io.scos.store.postgres.internal.Bigrams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 키워드 질의의 후보 경로 계획(순수, SQL 없음 — ADR-0014 P-4 부록 "드문 bigram 구동"). 후보 조건은 그대로다: 토큰을 원문에 포함하거나(2글자
 * 이상), 3글자 이상이면 공백 제거 본문에 토큰 trigram 하나를 포함한다. 원문에 토큰이 있으면 토큰의 bigram이 **전부** 있고, 공백 제거 본문에
 * trigram이 있으면 그 trigram의 bigram이 전부 있으므로, 후보는 토큰(또는 trigram)의 bigram 중 **가장 드문 것 하나**의 포스팅만 훑어 찾을
 * 수 있다. 점수는 찾은 유닛마다 토큰 bigram 전체로 다시 계산한다(호출자).
 *
 * <p>단계({@link Level})마다 그 단계의 경로로 찾은 유닛만으로 top-k를 구하고, 경로 밖 유닛이 낼 수 있는 점수 상한({@link #bound})보다 k번째
 * 점수가 크면 그것이 답이다({@link #settles}). 아니면 다음 단계로 간다. 상한(토큰별 합): 1글자를 훑지 않았으면 1(c/(1+c) &lt; 1), 정확
 * 매치를 못 찾았으면 2(커버리지 ≤ 1 + 0.5 + 0.5·c/(1+c) &lt; 2), 정확 매치는 찾았지만 trigram 경로를 못 찾은 3글자 이상 토큰은 1(정확하지
 * 않은 유닛의 점수는 커버리지 ≤ 1). 부동소수 합은 반올림이 단조라 정수 상한의 합을 넘지 않는다.
 */
final class KeywordPlan {
  /**
   * 후보 경로 하나: {@code driver} bigram의 포스팅 중 {@code compact}(공백 제거 본문, 아니면 원문)가 LIKE {@code pattern}인
   * 유닛. driver가 null이면 unit_texts 전체를 LIKE로 훑는다(1글자 토큰).
   */
  record Path(String driver, boolean compact, String pattern) {}

  /** 검색 단계. 뒤로 갈수록 경로가 넓다. */
  enum Level {
    /** 구동 bigram의 문서 빈도가 상한 이하인 경로만. */
    RARE,
    /** 정확 매치(원문 부분 문자열) 경로와 1글자 전체 스캔은 빈도와 무관하게, trigram 경로는 구동 bigram이 모두 드물 때만. */
    EXACT,
    /** 후보 전체. */
    ALL
  }

  /**
   * 질의 토큰 하나의 단계별 경로와 그 경로 밖 유닛이 이 토큰에서 받을 수 있는 점수의 상한(0이면 그 단계 경로가 이 토큰의 후보 전체다). ALL의 상한은 늘 0이다.
   */
  record Token(String text, List<List<Path>> pathsByLevel, List<Integer> boundsByLevel) {
    List<Path> paths(Level level) {
      return pathsByLevel.get(level.ordinal());
    }

    int bound(Level level) {
      return boundsByLevel.get(level.ordinal());
    }
  }

  private final List<Token> tokens;

  private KeywordPlan(List<Token> tokens) {
    this.tokens = tokens;
  }

  /** 문서 빈도를 물어야 할 bigram(2글자 이상 토큰의 bigram, 중복 없음, 처음 나온 순). */
  static List<String> bigramsOf(List<String> tokens) {
    Set<String> out = new LinkedHashSet<>();
    for (String t : tokens) out.addAll(Bigrams.bigramCounts(t).keySet());
    return List.copyOf(out);
  }

  /**
   * @param df bigram → 문서 빈도(상한 + 1에서 잘린 값이어도 된다). 없는 bigram은 흔한 것으로 본다
   * @param rareLimit 이 이하의 문서 빈도를 드물다고 본다
   */
  static KeywordPlan of(List<String> tokens, Map<String, Integer> df, int rareLimit) {
    List<Token> out = new ArrayList<>();
    for (String t : tokens) out.add(token(t, df, rareLimit));
    return new KeywordPlan(List.copyOf(out));
  }

  private static Token token(String t, Map<String, Integer> df, int rareLimit) {
    int length = t.codePointCount(0, t.length());
    String exact = like(t);
    if (length == 1) {
      List<Path> scan = List.of(new Path(null, false, exact));
      return new Token(t, List.of(List.of(), scan, scan), List.of(1, 0, 0));
    }
    if (length == 2) {
      List<Path> paths = List.of(new Path(t, false, exact));
      return isRare(t, df, rareLimit)
          ? new Token(t, List.of(paths, paths, paths), List.of(0, 0, 0))
          : new Token(t, List.of(List.of(), paths, paths), List.of(2, 0, 0));
    }
    // 원문 정확 매치는 공백 제거 본문에 trigram 전부를 남기므로 trigram 경로가 후보 전체를 덮는다
    Set<Path> trigramPaths = new LinkedHashSet<>();
    for (String g : Text.trigrams(t))
      trigramPaths.add(new Path(rarest(Bigrams.bigramCounts(g).keySet(), df), true, like(g)));
    List<Path> all = List.copyOf(trigramPaths);
    if (all.stream().allMatch(p -> isRare(p.driver(), df, rareLimit)))
      return new Token(t, List.of(all, all, all), List.of(0, 0, 0));
    String driver = rarest(Bigrams.bigramCounts(t).keySet(), df);
    List<Path> exactPath = List.of(new Path(driver, false, exact));
    return isRare(driver, df, rareLimit)
        ? new Token(t, List.of(exactPath, exactPath, all), List.of(1, 1, 0))
        : new Token(t, List.of(List.of(), exactPath, all), List.of(2, 1, 0));
  }

  /** 토큰별 경로(질의 토큰 순서, 되풀이된 토큰도 따로). */
  List<Token> tokens() {
    return tokens;
  }

  /** 그 단계의 경로(토큰 경로의 합집합, 중복 없음). */
  List<Path> paths(Level level) {
    Set<Path> out = new LinkedHashSet<>();
    for (Token t : tokens) out.addAll(t.paths(level));
    return List.copyOf(out);
  }

  /**
   * 그 단계가 빼놓은 경로의 구동 bigram(중복 없음) — 상한이 남은 토큰의 ALL 경로 중 그 단계에 없는 것. 호출자가 이 bigram들이 아주 흔한지(빼놓아서 아낄
   * 것이 있는지) 보고 그 단계를 시도할지 정한다.
   */
  List<String> omittedDrivers(Level level) {
    Set<String> out = new LinkedHashSet<>();
    for (Token t : tokens) {
      if (t.bound(level) == 0) continue;
      for (Path p : t.paths(Level.ALL))
        if (!t.paths(level).contains(p) && p.driver() != null) out.add(p.driver());
    }
    return List.copyOf(out);
  }

  /** 그 단계 경로가 찾지 못한 유닛이 낼 수 있는 점수의 상한(토큰 상한의 합). 0이면 그 단계가 곧 답이다. */
  int bound(Level level) {
    int bound = 0;
    for (Token t : tokens) bound += t.bound(level);
    return bound;
  }

  /**
   * 그 단계의 결과 {@code top}(k개 상한)이 최종 답인가: 상한이 0이거나, k개를 채웠고 k번째 점수가 상한보다 크다(동점이면 seq가 앞선 밖 유닛이 끼어들 수
   * 있다).
   */
  boolean settles(Level level, List<ScoredRef> top, int k) {
    int bound = bound(level);
    if (bound == 0 || k <= 0) return true;
    return top.size() >= k && top.get(k - 1).score() > bound;
  }

  private static boolean isRare(String bigram, Map<String, Integer> df, int rareLimit) {
    return df.getOrDefault(bigram, Integer.MAX_VALUE) <= rareLimit;
  }

  /** 문서 빈도가 가장 작은 bigram, 같으면 먼저 나온 것. */
  private static String rarest(Set<String> bigrams, Map<String, Integer> df) {
    return bigrams.stream()
        .min(Comparator.comparingInt(b -> df.getOrDefault(b, Integer.MAX_VALUE)))
        .orElseThrow();
  }

  private static String like(String s) {
    return "%" + Bigrams.escapeLike(s) + "%";
  }
}
