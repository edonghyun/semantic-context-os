package io.scos.spi.connector;

import java.util.Locale;

/** 재조정 집계의 고정된 상태 단위. 리소스 식별자는 지표 차원에 사용하지 않는다. */
public enum ReconciliationKind {
  PAGE,
  CONTAINER;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
