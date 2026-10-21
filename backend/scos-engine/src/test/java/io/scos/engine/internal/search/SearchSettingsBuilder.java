package io.scos.engine.internal.search;

/** 테스트용: SearchSettings.DEFAULT에서 시작해 테스트가 보는 값만 바꾼다(15개 위치 인자를 본문에서 지운다). */
final class SearchSettingsBuilder {
  private double vectorMinScore = SearchSettings.DEFAULT.vectorMinScore();
  private double vectorWeight = SearchSettings.DEFAULT.vectorWeight();
  private boolean linksEnabled = SearchSettings.DEFAULT.linksEnabled();
  private int linkParentWindow = SearchSettings.DEFAULT.linkParentWindow();
  private int perDocument = SearchSettings.DEFAULT.perDocument();
  private boolean tierPriority = SearchSettings.DEFAULT.tierPriority();

  static SearchSettingsBuilder defaults() {
    return new SearchSettingsBuilder();
  }

  SearchSettingsBuilder vectorMinScore(double value) {
    vectorMinScore = value;
    return this;
  }

  SearchSettingsBuilder vectorWeight(double value) {
    vectorWeight = value;
    return this;
  }

  SearchSettingsBuilder linksDisabled() {
    linksEnabled = false;
    return this;
  }

  SearchSettingsBuilder linkParentWindow(int value) {
    linkParentWindow = value;
    return this;
  }

  SearchSettingsBuilder perDocument(int value) {
    perDocument = value;
    return this;
  }

  SearchSettingsBuilder tierPriorityDisabled() {
    tierPriority = false;
    return this;
  }

  SearchSettings build() {
    SearchSettings d = SearchSettings.DEFAULT;
    return new SearchSettings(
        d.maxVariants(),
        d.variantWeight(),
        d.lexicalK(),
        d.vectorK(),
        vectorMinScore,
        d.rrfK(),
        d.lexicalWeight(),
        vectorWeight,
        d.archivedWeight(),
        linksEnabled,
        d.linkRankShift(),
        linkParentWindow,
        perDocument,
        tierPriority,
        d.rerankTopK());
  }
}
