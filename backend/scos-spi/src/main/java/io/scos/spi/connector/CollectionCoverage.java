package io.scos.spi.connector;

import java.util.Locale;

/** F5·불변식 6: 완료가 삭제(reconcile)의 근거가 될 수 있는지. 어댑터가 모드·체크포인트로 정한다. */
public enum CollectionCoverage {
  INCREMENTAL,
  COMPLETE_SNAPSHOT;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static CollectionCoverage fromWire(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }
}
