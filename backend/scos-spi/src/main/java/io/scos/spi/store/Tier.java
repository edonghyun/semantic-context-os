package io.scos.spi.store;

import java.util.Locale;

/** 유닛 티어(CONTRACTS B·C). 와이어 값은 TS와 같은 소문자. */
public enum Tier {
  RAW,
  COMPACTED,
  ARCHIVED;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Tier fromWire(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }
}
