package io.scos.engine.support.call;

import io.scos.engine.support.text.JsStrings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Retry-After 헤더 해석 규칙(ADR-0022 §4). 커넥터는 자기 공급자의 규칙 하나를 쥔다. 숫자는 JS {@code Number(header)} 해석이다.
 *
 * <ul>
 *   <li>{@link #nonNegativeSeconds}: Slack·Notion. 유한하고 0 이상이면 그 초, 아니면 없음. 빈 값은 0초다.
 *   <li>{@link #positiveSeconds}: Google Drive. 유한하고 0보다 크면 그 초, 아니면 없음. 빈 값·0은 없음이다.
 *   <li>{@link #secondsOrHttpDate}: Jira·Tiro. 숫자면 max(0, 초), 아니면 날짜(HTTP-date 세 형식, RFC 2822류, ISO
 *       8601)와 지금의 차이(음수면 0), 둘 다 아니면 없음. 오프셋 없는 날짜·시각은 UTC로 읽는다(HTTP-date는 GMT가 규약이다).
 * </ul>
 *
 * 밀리초 미만은 버린다.
 */
public final class RetryAfter {
  private static final double MILLIS_PER_SECOND = 1000;

  /** RFC 850: "Thursday, 15-Oct-26 00:00:30 GMT"(두 자리 연도는 2000년대). */
  private static final DateTimeFormatter RFC_850 =
      new DateTimeFormatterBuilder()
          .appendPattern("EEEE, dd-MMM-")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
          .appendPattern(" HH:mm:ss 'GMT'")
          .toFormatter(Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  /** asctime: "Thu Oct 15 00:00:30 2026"(공백은 하나로 줄여 읽는다). */
  private static final DateTimeFormatter ASCTIME =
      DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  private static final List<Function<String, Instant>> DATE_PARSERS =
      List.of(
          s -> OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(),
          s ->
              LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                  .toInstant(ZoneOffset.UTC),
          s ->
              LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)
                  .atStartOfDay(ZoneOffset.UTC)
                  .toInstant(),
          s ->
              ZonedDateTime.parse(
                      s.endsWith(" UTC") ? s.substring(0, s.length() - 4) + " GMT" : s,
                      DateTimeFormatter.RFC_1123_DATE_TIME)
                  .toInstant(),
          s -> ZonedDateTime.parse(s, RFC_850).toInstant(),
          s -> ZonedDateTime.parse(s.replaceAll("\\s+", " "), ASCTIME).toInstant());

  /** 유한한 초를 대기로 읽는 방법(없음이면 그 값은 받지 않는다). */
  private interface SecondsRule {
    Optional<Duration> of(double seconds);
  }

  private final SecondsRule seconds;
  private final Optional<Clock> dates;

  private RetryAfter(SecondsRule seconds, Optional<Clock> dates) {
    this.seconds = seconds;
    this.dates = dates;
  }

  public static RetryAfter nonNegativeSeconds() {
    return new RetryAfter(s -> s < 0 ? Optional.empty() : Optional.of(millis(s)), Optional.empty());
  }

  public static RetryAfter positiveSeconds() {
    return new RetryAfter(
        s -> s <= 0 ? Optional.empty() : Optional.of(millis(s)), Optional.empty());
  }

  /** 날짜는 이 시계의 지금과 견준다. */
  public static RetryAfter secondsOrHttpDate(Clock clock) {
    return new RetryAfter(s -> Optional.of(millis(Math.max(0, s))), Optional.of(clock));
  }

  /** 헤더 값(없으면 null)의 대기. */
  public Optional<Duration> read(@Nullable String raw) {
    if (raw == null) return Optional.empty();
    double number = JsStrings.number(raw);
    if (Double.isFinite(number)) return seconds.of(number);
    return dates.flatMap(clock -> untilDate(raw, clock.instant()));
  }

  private static Optional<Duration> untilDate(String raw, Instant now) {
    return date(raw.strip())
        .map(
            at -> {
              Duration wait = Duration.between(now, at);
              return wait.isNegative() ? Duration.ZERO : wait;
            });
  }

  private static Optional<Instant> date(String s) {
    for (Function<String, Instant> parser : DATE_PARSERS) {
      try {
        return Optional.of(parser.apply(s));
      } catch (DateTimeParseException e) {
        // 다음 형식
      }
    }
    return Optional.empty();
  }

  private static Duration millis(double seconds) {
    return Duration.ofMillis((long) (seconds * MILLIS_PER_SECOND));
  }
}
