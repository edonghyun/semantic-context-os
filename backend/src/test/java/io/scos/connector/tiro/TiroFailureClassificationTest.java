package io.scos.connector.tiro;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.internal.TiroApiException;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TS classifyTiroError: 상태 코드 → 실패 종류. 재시도 여부는 종류가 정한다(F7). 노트 경로의 403·404는 어댑터가 따로 다룬다. */
@DisplayName("Tiro 응답 상태는 TS classifyTiroError와 같이 분류한다")
class TiroFailureClassificationTest {
  static final Duration RETRY_AFTER = Duration.ofSeconds(3);

  @ParameterizedTest(name = "HTTP {0} → {1}")
  @CsvSource({
    "401, AUTHENTICATION_FAILED",
    "403, PERMISSION_DENIED",
    "404, PERMISSION_DENIED",
    "429, RATE_LIMITED",
    "400, INVALID_PROVIDER_RESPONSE",
    "402, INVALID_PROVIDER_RESPONSE",
    "405, INVALID_PROVIDER_RESPONSE",
    "428, INVALID_PROVIDER_RESPONSE",
    "430, INVALID_PROVIDER_RESPONSE",
    "499, INVALID_PROVIDER_RESPONSE",
    "500, DEPENDENCY_UNAVAILABLE",
    "502, DEPENDENCY_UNAVAILABLE",
    "503, DEPENDENCY_UNAVAILABLE",
    "302, DEPENDENCY_UNAVAILABLE",
    "399, DEPENDENCY_UNAVAILABLE"
  })
  @DisplayName("응답 상태는 TS와 같은 종류로 분류한다")
  void classifiesStatus(int status, Kind kind) {
    // when
    CallFailure failure = TiroAdapter.classify(new TiroApiException(status, Optional.empty()));

    // then
    assertThat(failure.kind()).isEqualTo(kind);
  }

  @Test
  @DisplayName("429는 Retry-After를 재시도 지연으로 넘긴다")
  void rateLimitedCarriesRetryAfter() {
    // when
    CallFailure failure = TiroAdapter.classify(new TiroApiException(429, Optional.of(RETRY_AFTER)));

    // then
    assertThat(failure.retryAfter()).contains(RETRY_AFTER);
  }

  @Test
  @DisplayName("응답이 없는 전송 실패는 dependency_unavailable이다")
  void transportFailureIsUnavailable() {
    // when
    CallFailure failure =
        TiroAdapter.classify(new UncheckedIOException(new IOException("connection reset")));

    // then
    assertThat(failure)
        .extracting(CallFailure::kind, CallFailure::message)
        .containsExactly(Kind.DEPENDENCY_UNAVAILABLE, "Tiro transport failed");
  }

  @Test
  @DisplayName("분류 메시지는 상태만 말한다")
  void messageNamesStatusOnly() {
    // when
    CallFailure failure = TiroAdapter.classify(new TiroApiException(503, Optional.empty()));

    // then
    assertThat(failure.message()).isEqualTo("Tiro request failed (HTTP 503)");
  }
}
