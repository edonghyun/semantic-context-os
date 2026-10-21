package io.scos.engine;

import java.util.List;

/** assemble 결과: 배치 순서의 묶음, 쓴 토큰, 예산 때문에 건너뛴 유닛이 있었는지, 공간 최신성. */
public record ContextBundle(
    List<BundleItem> items, int usedTokens, boolean truncated, Freshness freshness) {
  public ContextBundle {
    items = List.copyOf(items);
  }
}
