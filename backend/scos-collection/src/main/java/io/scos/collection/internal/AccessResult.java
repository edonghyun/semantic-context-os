package io.scos.collection.internal;

import java.util.Locale;

/** 본문 결과와 독립된 접근 단계 결과. COMPLETE는 병합 전 수집 완료다. */
public enum AccessResult {
  COMPLETE,
  INCOMPLETE,
  CLAIM_LOST,
  SKIPPED,
  UNSUPPORTED;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
