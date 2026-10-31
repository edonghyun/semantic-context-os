package io.scos.collection.internal;

import java.util.Locale;

/** run 상태. 와이어 값은 소문자 snake. */
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

  /** 연속 실패 수에 드는 상태. 실행 중·취소는 성공도 실패도 아니다. */
  public boolean countsAsFailure() {
    return this == FAILED || this == PARTIALLY_SUCCEEDED;
  }
}
