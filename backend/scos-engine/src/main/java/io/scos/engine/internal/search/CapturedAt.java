package io.scos.engine.internal.search;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 저장된 수집 시각(ISO 문자열 그대로 — 최신성 비교는 저장소가 문자열로 한다). 날짜 연산은 {@link #instant()}로 한 번 읽어서 한다. 오프셋 없는
 * 문자열처럼 읽을 수 없으면 시각을 모른다(CONTRACTS F-4).
 */
public record CapturedAt(String wire) {
  public Optional<Instant> instant() {
    try {
      return Optional.of(Instant.parse(wire));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
