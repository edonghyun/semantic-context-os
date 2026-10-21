package io.scos.engine;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 최신성 보고(CONTRACTS B9) — 값만. latestCapturedAt은 저장된 ISO 문자열 그대로다(MCP 골든과 바이트 동일). 시각 타입이 필요하면 {@link
 * #latestCapturedInstant()}를 쓴다.
 */
public record Freshness(
    Optional<String> latestCapturedAt, Optional<Double> staleHours, boolean stale) {
  public static final Freshness UNAVAILABLE =
      new Freshness(Optional.empty(), Optional.empty(), false);

  /** 오프셋 없는 문자열처럼 파싱할 수 없으면 비어 있다(CONTRACTS F-4). */
  public Optional<Instant> latestCapturedInstant() {
    return latestCapturedAt.flatMap(
        s -> {
          try {
            return Optional.of(Instant.parse(s));
          } catch (DateTimeParseException e) {
            return Optional.empty();
          }
        });
  }
}
