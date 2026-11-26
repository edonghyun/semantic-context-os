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

  /** 문서당 상한만 바꾼 값. 같으면 자신이다. */
  public SearchSettings withPerDocument(int cap) {
    if (cap == perDocument) return this;
    return new SearchSettings(
        maxVariants,
        variantWeight,
        lexicalK,
        vectorK,
        vectorMinScore,
        rrfK,
        lexicalWeight,
        vectorWeight,
        archivedWeight,
        linksEnabled,
        linkRankShift,
        linkParentWindow,
        cap,
        tierPriority,
        rerankTopK);
  }
}
