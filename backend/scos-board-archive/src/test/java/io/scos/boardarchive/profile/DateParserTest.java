package io.scos.boardarchive.profile;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

@DisplayName("날짜 파서는 지정 시간대와 수집 시각으로 해석한다")
class DateParserTest {
  final DateParser parser =
      new DateParser(
          ZoneId.of("Asia/Seoul"),
          List.of("yyyy.MM.dd"),
          Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC));

  @Test
  @DisplayName("절대 날짜는 지정 시간대의 자정이다")
  void absoluteWithZone() {
    // then
    assertThat(parser.parse("2026.11.17")).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));
  }

  @Test
  @DisplayName("시간 전 표기는 수집 시각 기준이다")
  void hoursAgo() {
    // then
    assertThat(parser.parse("3시간 전")).isEqualTo(Instant.parse("2026-11-17T21:00:00Z"));
  }

  @Test
  @DisplayName("분 전 표기는 수집 시각 기준이다")
  void minutesAgo() {
    // when
    var parsed = parser.parse("15분 전");
    // then
    assertThat(parsed).isEqualTo(Instant.parse("2026-11-17T23:45:00Z"));
  }

  @Test
  @DisplayName("일 전 표기는 수집 시각 기준이다")
  void daysAgo() {
    // when
    var parsed = parser.parse("2일 전");
    // then
    assertThat(parsed).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
  }

  @Test
  @DisplayName("어제와 시각을 함께 해석한다")
  void yesterdayWithTime() {
    // then
    assertThat(parser.parse("어제 13:20")).isEqualTo(Instant.parse("2026-11-17T04:20:00Z"));
  }

  @Test
  @DisplayName("해석할 수 없는 날짜는 거부한다")
  void unparsableThrows() {
    // then
    assertThatThrownBy(() -> parser.parse("unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unrecognized date representation");
  }
}
