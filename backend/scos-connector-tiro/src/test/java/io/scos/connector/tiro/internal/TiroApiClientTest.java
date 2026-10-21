package io.scos.connector.tiro.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.call.ProviderHttp;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Retry-After 해석(TS tiro-api-client.ts): JS Number가 유한하면 그 초(10진·16진은 공용 RetryAfter, 2진·8진은 Tiro),
 * 아니면 날짜(공용 RetryAfter — HTTP-date·ISO 8601)와 지금의 차이, 음수·지난 시각은 0, 읽지 못하면 없음. 기대값은 Node 22에서 TS 식을
 * 돌린 값이다. V8 Date.parse만 읽는 비표준 날짜는 편차(F14-D4).
 */
@DisplayName("Tiro Retry-After 해석은 TS tiro-api-client와 같다(F14-D4)")
class TiroApiClientTest {
  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  static final String TEN_SECONDS_LATER = "Thu, 15 Oct 2026 00:00:10 GMT";
  static final String TEN_SECONDS_AGO = "Wed, 14 Oct 2026 23:59:50 GMT";
  static final Duration TEN_SECONDS = Duration.ofSeconds(10);

  final TiroApiClient client =
      new TiroApiClient(
          "http://127.0.0.1:1/",
          new ProviderHttp(HttpClient.newHttpClient(), ProviderCallPolicy.DEFAULT_REQUEST_TIMEOUT),
          new ObjectMapper(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest(name = "\"{0}\" → {1}ms")
  @CsvSource(
      quoteCharacter = '\'',
      value = {
        "'3', 3000",
        "' 4 ', 4000",
        "'1.5', 1500",
        "'.5', 500",
        "'2e1', 20000",
        "'+1', 1000",
        "'0', 0",
        "'-2', 0",
        "'', 0",
        "'0x10', 16000",
        "'0X1f', 31000",
        "'0b11', 3000",
        "'0o7', 7000"
      })
  @DisplayName("초 값은 밀리초 대기로 읽는다")
  void readsSeconds(String header, long millis) {
    // when·then
    assertThat(client.retryAfter(header).map(Duration::toMillis)).contains(millis);
  }

  @Test
  @DisplayName("HTTP-date는 지금부터 남은 시간이다")
  void readsHttpDate() {
    // when·then
    assertThat(client.retryAfter(TEN_SECONDS_LATER)).contains(TEN_SECONDS);
  }

  @Test
  @DisplayName("지난 HTTP-date는 0이다")
  void pastHttpDateIsZero() {
    // when·then
    assertThat(client.retryAfter(TEN_SECONDS_AGO)).contains(Duration.ZERO);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"soon", "1d", "Infinity", "-0x10", "0x", "0b2", "0o8", "-0b1"})
  @DisplayName("읽지 못하는 값은 없음이다")
  void unreadableIsAbsent(String header) {
    // when·then
    assertThat(client.retryAfter(header)).isEmpty();
  }

  /** JS Number가 Infinity로 읽는 값 — TS는 Date.parse로 넘어가 NaN, 곧 없음(기본 지연)이다. */
  static List<String> infiniteNumbers() {
    return List.of(
        "1e400", "0x" + "f".repeat(300), "0b" + "1".repeat(1100), "0o" + "7".repeat(400));
  }

  @ParameterizedTest(name = "{index}")
  @MethodSource("infiniteNumbers")
  @DisplayName("Infinity가 되는 수는 없음이다")
  void infiniteIsAbsent(String header) {
    // when·then
    assertThat(client.retryAfter(header)).isEmpty();
  }

  static final String ISO_TEN_SECONDS_LATER = "2026-10-15T00:00:10Z";

  @Test
  @DisplayName("ISO 8601 날짜도 지금부터 남은 시간이다")
  void readsIsoDate() {
    // when·then
    assertThat(client.retryAfter(ISO_TEN_SECONDS_LATER)).contains(TEN_SECONDS);
  }

  /**
   * V8 Date.parse만 날짜로 읽는 비표준 문자열(TS는 과거 날짜 → 0ms). 공용 RetryAfter는 HTTP-date 세 형식과 ISO 8601만
   * 받는다(CONTRACTS F14-D4).
   */
  static final String V8_ONLY_DATE = "1.2.3";

  @Test
  @DisplayName("V8만 날짜로 읽는 비표준 문자열은 없음이다(F14-D4)")
  void v8OnlyDateIsAbsent() {
    // when
    Optional<Duration> retryAfter = client.retryAfter(V8_ONLY_DATE);

    // then
    assertThat(retryAfter).isEmpty();
  }

  @Test
  @DisplayName("헤더가 없으면 없음이다")
  void missingHeaderIsAbsent() {
    // when
    Optional<Duration> retryAfter = client.retryAfter(null);

    // then
    assertThat(retryAfter).isEmpty();
  }
}
