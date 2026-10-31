package io.scos.spi.connector;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 본문 읽기 경로·직전 성공 탐색·소스별 관측 유예의 비교 기준. 저장 표현을 노출하지 않는다. */
public record ReconciliationContext(
    Path path,
    Optional<Instant> successfulExploration,
    Optional<Instant> editedAt,
    boolean bootstrap,
    Duration gracePeriod) {
  public enum Path {
    INCREMENTAL,
    FULL
  }
}
