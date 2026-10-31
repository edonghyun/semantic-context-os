package io.scos.engine.internal.search.plan;

/** 검색 값. 순서는 레시피 코드가 정한다. */
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
      new SearchSettings(
          /* maxVariants */ 4,
          /* variantWeight */ 0.9,
          /* lexicalK */ 50,
          /* vectorK */ 50,
          /* vectorMinScore */ 0.5,
          /* rrfK */ 60,
          /* lexicalWeight */ 1,
          /* vectorWeight */ 1,
          /* archivedWeight */ 0.3,
          /* linksEnabled */ true,
          /* linkRankShift */ 5,
          /* linkParentWindow */ 50,
          /* perDocument */ 2,
          /* tierPriority */ true,
          /* rerankTopK */ 30);
}
