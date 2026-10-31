package io.scos.engine.internal.search.plan;

import io.scos.engine.Effort;

/** 노력 수준별 검색 값. 튜닝 숫자는 여기에만 둔다(초안 — Task 7 측정 후 조정). */
final class Presets {
  private Presets() {}

  private static final SearchSettings LOW =
      new SearchSettings(
          // 변형·링크는 지연 대부분이라 끈다
          /* maxVariants */ 0,
          SearchSettings.DEFAULT.variantWeight(),
          // 후보 폭을 줄여 빠르게
          /* lexicalK */ 20,
          /* vectorK */ 20,
          SearchSettings.DEFAULT.vectorMinScore(),
          SearchSettings.DEFAULT.rrfK(),
          SearchSettings.DEFAULT.lexicalWeight(),
          SearchSettings.DEFAULT.vectorWeight(),
          SearchSettings.DEFAULT.archivedWeight(),
          /* linksEnabled */ false,
          SearchSettings.DEFAULT.linkRankShift(),
          SearchSettings.DEFAULT.linkParentWindow(),
          SearchSettings.DEFAULT.perDocument(),
          SearchSettings.DEFAULT.tierPriority(),
          SearchSettings.DEFAULT.rerankTopK());

  private static final SearchSettings HIGH =
      new SearchSettings(
          SearchSettings.DEFAULT.maxVariants(),
          SearchSettings.DEFAULT.variantWeight(),
          // 후보 폭을 두 배로 넓혀 재현율 우선
          /* lexicalK */ 100,
          /* vectorK */ 100,
          SearchSettings.DEFAULT.vectorMinScore(),
          SearchSettings.DEFAULT.rrfK(),
          SearchSettings.DEFAULT.lexicalWeight(),
          SearchSettings.DEFAULT.vectorWeight(),
          SearchSettings.DEFAULT.archivedWeight(),
          SearchSettings.DEFAULT.linksEnabled(),
          SearchSettings.DEFAULT.linkRankShift(),
          /* linkParentWindow */ 100,
          SearchSettings.DEFAULT.perDocument(),
          SearchSettings.DEFAULT.tierPriority(),
          SearchSettings.DEFAULT.rerankTopK());

  static SearchSettings of(Effort effort) {
    return switch (effort) {
      case LOW -> LOW;
      case MEDIUM -> SearchSettings.DEFAULT;
      case HIGH -> HIGH;
    };
  }

  /** 재정렬은 LLM 호출이라 LOW에서만 끈다. */
  static boolean rerank(Effort effort) {
    return effort != Effort.LOW;
  }
}
