package io.scos.spi.connector;

import java.util.Locale;

/** TS CollectionMode: snapshot은 어댑터에게 처음부터 다시 걷게 한다(놓친 이벤트 복구). */
public enum CollectionMode {
  INCREMENTAL,
  SNAPSHOT;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
