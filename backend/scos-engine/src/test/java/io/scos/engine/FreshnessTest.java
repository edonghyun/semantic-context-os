package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 최신성 값(단계 2 §1.2): 저장된 ISO 문자열은 그대로 두고, 시각 타입은 파싱할 수 있을 때만 낸다(CONTRACTS F-4). */
@DisplayName("Freshness는 저장된 시각 문자열을 그대로 두고 Instant를 따로 낸다")
class FreshnessTest {
  static final String CAPTURED_AT = "2026-10-12T23:00:00.000Z";

  static Freshness latest(String capturedAt) {
    return new Freshness(Optional.of(capturedAt), Optional.of(1.0), false);
  }

  @Test
  @DisplayName("ISO 시각이면 latestCapturedInstant는 그 시각이다")
  void parsesIsoInstant() {
    // when
    Optional<Instant> instant = latest(CAPTURED_AT).latestCapturedInstant();

    // then
    assertThat(instant).contains(Instant.parse(CAPTURED_AT));
  }

  @Test
  @DisplayName("오프셋 없는 시각이면 latestCapturedInstant는 비어 있다")
  void offsetlessIsEmpty() {
    // when
    Optional<Instant> instant = latest("2026-10-12T23:00:00").latestCapturedInstant();

    // then
    assertThat(instant).isEmpty();
  }

  @Test
  @DisplayName("UNAVAILABLE이면 latestCapturedInstant는 비어 있다")
  void unavailableIsEmpty() {
    // when
    Optional<Instant> instant = Freshness.UNAVAILABLE.latestCapturedInstant();

    // then
    assertThat(instant).isEmpty();
  }
}
