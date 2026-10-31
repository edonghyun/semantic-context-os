package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CallFailure;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("HTTP 상태는 공급자 실패의 재시도 계약으로 나뉜다")
class HttpStatusFailureTest {
  static final Optional<Duration> RETRY_AFTER = Optional.of(Duration.ofSeconds(30));

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "401, authentication_failed",
    "403, permission_denied",
    "404, permission_denied",
    "400, invalid_provider_response",
    "422, invalid_provider_response",
    "499, invalid_provider_response",
    "500, dependency_unavailable",
    "503, dependency_unavailable",
    "302, dependency_unavailable"
  })
  @DisplayName("상태마다 실패 종류가 정해진다")
  void classifiesByStatus(int status, String kind) {
    // when
    CallFailure failure = HttpStatusFailure.of(status).toCallFailure("m", RETRY_AFTER);

    // then
    assertThat(failure.kind().wire()).isEqualTo(kind);
  }

  @Test
  @DisplayName("429는 Retry-After를 실은 요청 제한이다")
  void tooManyRequestsIsRateLimited() {
    // when
    CallFailure failure = HttpStatusFailure.of(429).toCallFailure("m", RETRY_AFTER);

    // then
    assertThat(failure).isEqualTo(CallFailure.rateLimited("m", RETRY_AFTER));
  }

  @Test
  @DisplayName("공급자가 알린 요청 제한 이유가 있으면 403도 요청 제한이다")
  void rateLimitReasonWinsOverForbidden() {
    // when
    CallFailure failure = new HttpStatusFailure(403, true).toCallFailure("m", RETRY_AFTER);

    // then
    assertThat(failure.kind().wire()).isEqualTo("rate_limited");
  }
}
