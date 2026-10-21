package io.scos.engine.support.call;

import java.math.BigInteger;
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
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Retry-After 헤더 해석(ADR-0022 §4, F4 리뷰 A-M8) — 커넥터마다 TS가 쓰던 두 의미를 공용으로 둔다.
 *
 * <ul>
 *   <li>{@link #seconds}: TS Slack·Notion. JS {@code Number(header)}가 유한하고 0 이상이면 그 초, 아니면 없음. 빈 값은
 *       0초다.
 *   <li>{@link #positiveSeconds}: TS Google Drive. JS {@code Number(header)}가 유한하고 0보다 크면 그 초, 아니면
 *       없음. 빈 값·0은 없음이다.
 *   <li>{@link #secondsOrHttpDate}: TS Jira. 숫자면 max(0, 초), 아니면 {@code Date.parse}(HTTP-date 세 형식,
 *       RFC 2822류, ISO 8601)와 지금의 차이(음수면 0), 둘 다 아니면 없음. 오프셋 없는 날짜·시각은 UTC로 읽는다(TS fixture는 TZ=UTC로
 *       만든다 — HTTP-date는 GMT가 규약이다).
 * </ul>
 *
 * 밀리초 미만은 버린다.
 */
public final class RetryAfter {
  /** JS Number가 유한한 10진수로 읽는 모양(앞뒤 공백은 뗀다). */
  private static final Pattern DECIMAL =
      Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

  private static final Pattern HEX = Pattern.compile("^0[xX][0-9a-fA-F]+$");
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

  private RetryAfter() {}

  /** TS Slack·Notion: 유한하고 0 이상인 초만. */
  public static Optional<Duration> seconds(String raw) {
    if (raw == null) return Optional.empty();
    OptionalDouble seconds = jsNumber(raw);
    if (seconds.isEmpty() || !Double.isFinite(seconds.getAsDouble()) || seconds.getAsDouble() < 0)
      return Optional.empty();
    return Optional.of(millis(seconds.getAsDouble()));
  }

  /** TS Google Drive: 유한하고 0보다 큰 초만. */
  public static Optional<Duration> positiveSeconds(String raw) {
    if (raw == null) return Optional.empty();
    OptionalDouble seconds = jsNumber(raw);
    if (seconds.isEmpty() || !Double.isFinite(seconds.getAsDouble()) || seconds.getAsDouble() <= 0)
      return Optional.empty();
    return Optional.of(millis(seconds.getAsDouble()));
  }

  /** TS Jira: 숫자면 max(0, 초), 아니면 날짜와 지금의 차이(음수면 0). */
  public static Optional<Duration> secondsOrHttpDate(String raw, Instant now) {
    if (raw == null) return Optional.empty();
    OptionalDouble seconds = jsNumber(raw);
    if (seconds.isPresent() && Double.isFinite(seconds.getAsDouble()))
      return Optional.of(millis(Math.max(0, seconds.getAsDouble())));
    return date(raw.strip())
        .map(
            at -> {
              Duration wait = Duration.between(now, at);
              return wait.isNegative() ? Duration.ZERO : wait;
            });
  }

  /** JS Number(문자열): 빈 값은 0, 10진수·16진수·(±)Infinity, 그 밖은 없음(NaN). */
  static OptionalDouble jsNumber(String raw) {
    String s = raw.strip();
    if (s.isEmpty()) return OptionalDouble.of(0);
    if (DECIMAL.matcher(s).matches()) return OptionalDouble.of(Double.parseDouble(s));
    if (HEX.matcher(s).matches())
      return OptionalDouble.of(new BigInteger(s.substring(2), 16).doubleValue());
    return switch (s) {
      case "Infinity", "+Infinity" -> OptionalDouble.of(Double.POSITIVE_INFINITY);
      case "-Infinity" -> OptionalDouble.of(Double.NEGATIVE_INFINITY);
      default -> OptionalDouble.empty();
    };
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
