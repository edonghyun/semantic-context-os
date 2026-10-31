package io.scos.collection.internal;

import io.scos.spi.connector.ReconciliationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 재조정 명세가 사용하는 기본 관측 유예. 운영 SPI에 공급자 정책을 넣지 않는다. */
final class ReconciliationContexts {
  private static final Duration DEFAULT_GRACE = Duration.ofMinutes(30);

  private ReconciliationContexts() {}

  static ReconciliationContext withDefaultGrace(
      ReconciliationContext.Path path,
      Optional<Instant> successfulExploration,
      Optional<Instant> editedAt,
      boolean bootstrap) {
    return new ReconciliationContext(
        path, successfulExploration, editedAt, bootstrap, DEFAULT_GRACE);
  }
}
