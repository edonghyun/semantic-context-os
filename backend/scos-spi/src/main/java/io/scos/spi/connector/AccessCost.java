package io.scos.spi.connector;

import java.util.OptionalLong;

/** 접근 시도 비용. 호출 수를 측정하지 못한 원천은 empty이며 실제 0과 구분한다. */
public record AccessCost(long targetCount, OptionalLong callCount, long elapsedMs) {
  public AccessCost {
    if (targetCount < 0 || elapsedMs < 0 || (callCount.isPresent() && callCount.getAsLong() < 0))
      throw new IllegalArgumentException("Access cost must be non-negative");
  }
}
