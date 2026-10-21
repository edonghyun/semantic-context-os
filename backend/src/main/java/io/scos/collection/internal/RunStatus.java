package io.scos.collection.internal;

import java.util.Locale;

/** TS SyncRunStatus. 와이어 값은 소문자 snake. */
public enum RunStatus {
  RUNNING,
  SUCCEEDED,
  SUCCEEDED_WITH_REJECTIONS,
  PARTIALLY_SUCCEEDED,
  FAILED,
  CANCELLED;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static RunStatus fromWire(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }

  public boolean isSuccess() {
    return this == SUCCEEDED || this == SUCCEEDED_WITH_REJECTIONS;
  }
}
