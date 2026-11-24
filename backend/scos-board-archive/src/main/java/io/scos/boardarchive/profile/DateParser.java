package io.scos.boardarchive.profile;

import java.time.*;
import java.time.format.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.regex.*;

public final class DateParser {
  private static final Pattern AGO = Pattern.compile("(\\d+)\\s*(분|시간|일)\\s*전");
  private static final Pattern YESTERDAY =
      Pattern.compile("어제(?:\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?))?");
  private final ZoneId zone;
  private final List<DateTimeFormatter> formats;
  private final Clock clock;

  public DateParser(ZoneId zone, List<String> patterns, Clock clock) {
    this.zone = zone;
    formats = patterns.stream().map(DateTimeFormatter::ofPattern).toList();
    this.clock = clock;
  }

  public Instant parse(String text) {
    String value = text.strip();
    if (value.equals("방금")) return clock.instant();
    Matcher ago = AGO.matcher(value);
    if (ago.matches()) return clock.instant().minus(relative(ago));
    Matcher yesterday = YESTERDAY.matcher(value);
    if (yesterday.matches()) {
      String time = yesterday.group(1);
      return LocalDate.now(clock.withZone(zone))
          .minusDays(1)
          .atTime(time == null ? LocalTime.MIDNIGHT : LocalTime.parse(time))
          .atZone(zone)
          .toInstant();
    }
    for (DateTimeFormatter format : formats) {
      try {
        return absolute(
            format.parseBest(
                value,
                ZonedDateTime::from,
                OffsetDateTime::from,
                LocalDateTime::from,
                LocalDate::from));
      } catch (DateTimeParseException ignored) {
        /* Try the next declared representation. */
      }
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Unrecognized date representation", e);
    }
  }

  private Duration relative(Matcher m) {
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2)) {
      case "분" -> Duration.ofMinutes(n);
      case "시간" -> Duration.ofHours(n);
      case "일" -> Duration.ofDays(n);
      default -> throw new IllegalArgumentException("Unsupported relative unit");
    };
  }

  private Instant absolute(TemporalAccessor parsed) {
    if (parsed instanceof ZonedDateTime zoned) return zoned.toInstant();
    if (parsed instanceof OffsetDateTime offset) return offset.toInstant();
    if (parsed instanceof LocalDateTime local) return local.atZone(zone).toInstant();
    return LocalDate.from(parsed).atStartOfDay(zone).toInstant();
  }
}
