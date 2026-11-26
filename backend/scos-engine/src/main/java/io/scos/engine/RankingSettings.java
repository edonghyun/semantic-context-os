package io.scos.engine;

import io.scos.engine.support.text.Text;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 인스턴스가 정하는 검색 순위 값({@link ContextEngineBuilder#ranking}). {@link #DEFAULT}는 설정을 주지 않은 엔진의 순위와
 * 같다(CONTRACTS B13).
 *
 * @param recency 날짜 계수
 * @param perDocument 한 문서가 결과에 남길 조각 수. 0이면 상한 없음
 * @param currentWindow profile=current가 최신순으로 고르는 관련 상위 창(limit의 배수, 1 이상)
 * @param sources 원천 별명과 색인에 없는 원천의 별명
 */
public record RankingSettings(
    Recency recency, int perDocument, int currentWindow, Sources sources) {
  public static final RankingSettings DEFAULT =
      new RankingSettings(Recency.DEFAULT, 2, 2, Sources.NONE);

  public RankingSettings {
    if (recency == null || sources == null)
      throw new IllegalArgumentException("recency and sources are required");
    if (perDocument < 0) throw new IllegalArgumentException("perDocument must be >= 0");
    if (currentWindow < 1) throw new IllegalArgumentException("currentWindow must be >= 1");
  }

  /**
   * 날짜 계수 {@code 1 + weight·dailyRetention^days}(days = capturedAt 뒤 지난 일수, 0 아래로는 가지 않는다).
   *
   * @param when auto 질의에 계수를 붙이는 조건. profile=current와 assemble은 언제나 붙인다
   */
  public record Recency(double weight, double dailyRetention, When when) {
    public static final Recency DEFAULT = new Recency(0.1, 0.99, When.MARKED);

    /** auto 질의에 날짜 계수를 붙이는 조건. */
    public enum When {
      /** 질의에 최신성 표지(최근·오늘·latest…)가 있고 과거·날짜·방법 표지가 없을 때만. */
      MARKED,
      /** 언제나. */
      ALWAYS
    }

    public Recency {
      Checks.requireWeight("recency.weight", weight);
      if (!(dailyRetention > 0 && dailyRetention <= 1))
        throw new IllegalArgumentException("recency.dailyRetention must be in (0, 1]");
      if (when == null) throw new IllegalArgumentException("recency.when is required");
    }

    /** 반감기로 준 날짜 계수: halfLifeDays일 뒤 가산이 절반이 된다(dailyRetention = 0.5^(1/halfLifeDays)). */
    public static Recency halfLife(double weight, double halfLifeDays, When when) {
      if (!(halfLifeDays > 0) || Double.isInfinite(halfLifeDays))
        throw new IllegalArgumentException("halfLifeDays must be positive");
      return new Recency(weight, Math.pow(0.5, 1 / halfLifeDays), when);
    }
  }

  /**
   * 원천 별명. 질의(NFKC·소문자)에서 별명이 낱말 시작 위치에 나오면 그 원천을 말한 것이다.
   *
   * @param aliases 색인에 있는 원천 id별 별명. 말한 원천의 후보 점수에 {@code 1 + weight}를 곱한다
   * @param notIndexed 색인에 없는 것으로 알려진 원천의 별명. 질의가 이것만 말하면 search 결과는 0건이다
   */
  public record Sources(Map<String, Set<String>> aliases, Set<String> notIndexed, double weight) {
    public static final Sources NONE = new Sources(Map.of(), Set.of(), 0);

    public Sources {
      if (aliases == null || notIndexed == null)
        throw new IllegalArgumentException("aliases and notIndexed are required");
      Checks.requireWeight("sources.weight", weight);
      aliases = normalized(aliases);
      notIndexed = normalized("notIndexed", notIndexed);
      Set<String> seen = new HashSet<>(notIndexed);
      for (Set<String> names : aliases.values())
        for (String name : names)
          if (!seen.add(name)) throw new IllegalArgumentException("alias used twice: " + name);
    }

    private static Map<String, Set<String>> normalized(Map<String, Set<String>> aliases) {
      Map<String, Set<String>> out = new HashMap<>();
      aliases.forEach(
          (source, names) -> {
            if (source == null || source.isBlank())
              throw new IllegalArgumentException("source id must not be blank");
            out.put(source, normalized(source, names));
          });
      return Map.copyOf(out);
    }

    private static Set<String> normalized(String owner, Set<String> names) {
      if (names.stream().anyMatch(n -> n == null || n.isBlank()))
        throw new IllegalArgumentException("blank alias for " + owner);
      return names.stream()
          .map(n -> Text.nfkcLower(n.strip()))
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  /** 중첩 값의 검사. 바깥 클래스의 정적 초기화(DEFAULT)를 건드리지 않게 따로 둔다. */
  private static final class Checks {
    private Checks() {}

    static void requireWeight(String field, double weight) {
      if (!(weight >= 0) || Double.isInfinite(weight))
        throw new IllegalArgumentException(field + " must be a finite number >= 0");
    }
  }
}
