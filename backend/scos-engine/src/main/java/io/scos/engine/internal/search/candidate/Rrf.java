package io.scos.engine.internal.search.candidate;

import io.scos.engine.support.PureFunctions;

/**
 * Reciprocal Rank Fusion 항(CONTRACTS F-7): weight / (k + rank + 1), rank는 0부터. 검색·이웃 문서가 같은 식을 쓴다.
 */
@PureFunctions
public final class Rrf {
  private Rrf() {}

  public static double term(double weight, int k, int rank) {
    return weight / (k + rank + 1);
  }
}
