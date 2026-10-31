package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Retry-After 해석(ADR-0022 §4). 초 해석은 TS Slack·Notion 클라이언트와 같다: JS `Number(header)`가 유한하고 0 이상이면 그
 * 초, 아니면 없음 — 빈 값은 0초다. 양수 초 해석(Google Drive)은 0·빈 값도 없음이다. 초 또는 HTTP-date 해석(Jira)은 커넥터 fixture의
 * TS 표(`JiraApiClientTest`)가, Drive 해석은 `GoogleDriveSessionTest`의 TS 표가 고정한다.
 */
@DisplayName("Retry-After 초 해석은 TS 클라이언트와 같다(ADR-0022 §4)")
class RetryAfterTest {
  @ParameterizedTest(name = "\"{0}\" → {1}ms")
  @CsvSource(
      delimiter = '|',
      value = {"12|12000", "1.5|1500", "0|0", "' 5 '|5000", "1e3|1000000", "0x10|16000", "''|0"})
  @DisplayName("JS Number로 읽히는 0 이상의 값은 그만큼 기다린다")
  void readsNonNegativeSeconds(String raw, long millis) {
    // when
    Optional<Duration> wait = RetryAfter.nonNegativeSeconds().read(raw);

    // then
    assertThat(wait).map(Duration::toMillis).contains(millis);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"-3", "abc", "12d", "Infinity", "NaN", "Thu, 15 Oct 2026 00:00:30 GMT"})
  @DisplayName("음수이거나 JS Number가 유한한 수로 읽지 못하면 없다")
  void ignoresNegativeOrNonNumeric(String raw) {
    // when
    Optional<Duration> wait = RetryAfter.nonNegativeSeconds().read(raw);

    // then
    assertThat(wait).isEmpty();
  }

  @ParameterizedTest(name = "\"{0}\" → {1}ms")
  @CsvSource(
      delimiter = '|',
      value = {"0b101|5000", "0o17|15000"})
  @DisplayName("2진·8진 표기도 JS Number처럼 읽는다")
  void readsBinaryAndOctal(String raw, long millis) {
    // when
    Optional<Duration> wait = RetryAfter.nonNegativeSeconds().read(raw);

    // then
    assertThat(wait).map(Duration::toMillis).contains(millis);
  }

  /** 표시 이름은 16진 코드 포인트(U+00A0)다. */
  static List<Named<Integer>> jsWhitespace() {
    return Stream.of(0x00A0, 0xFEFF).map(c -> Named.of(String.format("U+%04X", c), c)).toList();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("jsWhitespace")
  @DisplayName("JS 공백(NBSP·BOM)을 앞에 붙여도 그 초를 읽는다")
  void stripsJsWhitespace(int whitespace) {
    // when
    Optional<Duration> wait =
        RetryAfter.nonNegativeSeconds().read(Character.toString(whitespace) + "5");

    // then
    assertThat(wait).map(Duration::toMillis).contains(5000L);
  }

  @Test
  @DisplayName("JS가 공백으로 보지 않는 제어 문자가 붙으면 없다")
  void controlCharacterIsNotWhitespace() {
    // when
    Optional<Duration> wait = RetryAfter.nonNegativeSeconds().read("\u001C5");

    // then
    assertThat(wait).isEmpty();
  }

  @Test
  @DisplayName("헤더가 없으면 없다")
  void absentHeaderIsEmpty() {
    // when
    Optional<Duration> wait = RetryAfter.nonNegativeSeconds().read(null);

    // then
    assertThat(wait).isEmpty();
  }

  @Nested
  @DisplayName("양수 초만 받으면(Google Drive)")
  class PositiveSeconds {
    @ParameterizedTest(name = "\"{0}\" → {1}ms")
    @CsvSource(
        delimiter = '|',
        value = {"12|12000", "1.5|1500", "' 5 '|5000", "1e3|1000000", "0x10|16000"})
    @DisplayName("JS Number로 읽히는 0보다 큰 값은 그만큼 기다린다")
    void readsPositiveSeconds(String raw, long millis) {
      // when
      Optional<Duration> wait = RetryAfter.positiveSeconds().read(raw);

      // then
      assertThat(wait).map(Duration::toMillis).contains(millis);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0", "", "-3", "abc", "Infinity", "Thu, 15 Oct 2026 00:00:30 GMT"})
    @DisplayName("0·빈 값·음수·유한한 수가 아닌 값은 없다")
    void ignoresZeroNegativeOrNonNumeric(String raw) {
      // when
      Optional<Duration> wait = RetryAfter.positiveSeconds().read(raw);

      // then
      assertThat(wait).isEmpty();
    }

    @Test
    @DisplayName("헤더가 없으면 없다")
    void absentHeaderIsEmpty() {
      // when
      Optional<Duration> wait = RetryAfter.positiveSeconds().read(null);

      // then
      assertThat(wait).isEmpty();
    }
  }
}
