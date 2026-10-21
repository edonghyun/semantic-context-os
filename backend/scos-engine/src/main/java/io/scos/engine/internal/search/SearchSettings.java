package io.scos.engine.internal.search;

/** 검색 값(TS SearchSettings.fromEngineConfig(DEFAULT_CONFIG)과 같은 기본값). 순서는 레시피 코드가 정한다. */
public record SearchSettings(
    int maxVariants,
    double variantWeight,
    int lexicalK,
    int vectorK,
    double vectorMinScore,
    int rrfK,
    double lexicalWeight,
    double vectorWeight,
    double archivedWeight,
    boolean linksEnabled,
    int linkRankShift,
    int linkParentWindow,
    int perDocument,
    boolean tierPriority,
    int rerankTopK) {
  public static final SearchSettings DEFAULT =
      new SearchSettings(4, 0.9, 50, 50, 0.5, 60, 1, 1, 0.3, true, 5, 50, 2, true, 30);
}
