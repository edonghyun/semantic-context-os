package io.scos.connector.slack.internal;

import io.scos.spi.connector.SourceOrder;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack 메시지 시각 "초.마이크로초". 공급자가 준 글자를 그대로 지닌다 — 키·워터마크·체크포인트에 그 글자가 저장되므로 같음은 글자로, 앞뒤는 마이크로초로 본다.
 *
 * <p>두 기준이 어긋나므로("1.5"와 "1.500000"은 같지 않지만 같은 시각) 자연 순서를 두지 않는다. 앞뒤는 {@link #CHRONOLOGICAL}로 묻는다.
 */
public record SlackTs(String text) {
  /** 마이크로초 시간순. "1.5"와 "1.500000"을 0으로 보므로 equals와 어긋난다 — 정렬 컬렉션에 쓰면 같은 시각의 다른 글자는 하나로 합쳐진다. */
  public static final Comparator<SlackTs> CHRONOLOGICAL = Comparator.comparing(SlackTs::micros);

  static final String ORDER_SCHEME = "slack.message-state.v1";
  private static final Pattern TS = Pattern.compile("^(\\d{1,20})\\.(\\d{1,6})$");
  private static final BigInteger MICROS_PER_SECOND = BigInteger.valueOf(1_000_000);
  private static final BigInteger MICROS_PER_MILLI = BigInteger.valueOf(1000);
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public SlackTs {
    if (!TS.matcher(text).matches()) throw new IllegalArgumentException("Invalid Slack timestamp");
  }

  public static Optional<SlackTs> parse(String text) {
    return TS.matcher(text).matches() ? Optional.of(new SlackTs(text)) : Optional.empty();
  }

  static SlackTs ofEpochMillis(long millis) {
    return ofMicros(BigInteger.valueOf(millis).multiply(MICROS_PER_MILLI));
  }

  private static SlackTs ofMicros(BigInteger micros) {
    BigInteger[] qr = micros.divideAndRemainder(MICROS_PER_SECOND);
    return new SlackTs(qr[0] + "." + padStart(qr[1].toString(), 6));
  }

  private BigInteger micros() {
    Matcher m = matched();
    return new BigInteger(m.group(1))
        .multiply(MICROS_PER_SECOND)
        .add(new BigInteger(padEnd(m.group(2))));
  }

  /** 정수 초 부분(분·일 경계 계산용). */
  public long epochSecond() {
    return Long.parseLong(text.substring(0, text.indexOf('.')));
  }

  public boolean isBefore(SlackTs other) {
    return CHRONOLOGICAL.compare(this, other) < 0;
  }

  /** 둘 중 뒤의 시각(없는 쪽은 건너뛴다). 같으면 current를 지킨다. */
  static Optional<SlackTs> later(Optional<SlackTs> current, Optional<SlackTs> candidate) {
    if (candidate.isEmpty()) return current;
    return Optional.of(current.map(c -> c.laterOf(candidate.get())).orElse(candidate.get()));
  }

  /** 둘 중 뒤의 시각. 같으면 이쪽을 지킨다. */
  public SlackTs laterOf(SlackTs candidate) {
    return CHRONOLOGICAL.compare(candidate, this) > 0 ? candidate : this;
  }

  /** seconds초 앞의 시각(0 아래로는 가지 않는다). 0초면 글자를 그대로 지킨다. */
  public SlackTs minusSeconds(int seconds) {
    if (seconds == 0) return this;
    BigInteger shift = BigInteger.valueOf(seconds).multiply(MICROS_PER_SECOND);
    BigInteger micros = micros();
    return ofMicros(micros.compareTo(shift) > 0 ? micros.subtract(shift) : BigInteger.ZERO);
  }

  /** 마이크로초를 밀리초로 내린 ISO UTC 표기. */
  public String toIsoMillis() {
    return ISO_MILLIS.format(
        Instant.ofEpochMilli(micros().divide(MICROS_PER_MILLI).longValueExact()));
  }

  /** 원본 순서 위치: 초 20자리 + "." + 마이크로초 6자리. */
  public SourceOrder order() {
    Matcher m = matched();
    return new SourceOrder(ORDER_SCHEME, padStart(m.group(1), 20) + "." + padEnd(m.group(2)));
  }

  @Override
  public String toString() {
    return text;
  }

  private Matcher matched() {
    Matcher m = TS.matcher(text);
    if (!m.matches()) throw new IllegalStateException("Invalid Slack timestamp");
    return m;
  }

  private static String padEnd(String fraction) {
    StringBuilder b = new StringBuilder(fraction);
    while (b.length() < 6) b.append('0');
    return b.toString();
  }

  private static String padStart(String digits, int width) {
    StringBuilder b = new StringBuilder();
    for (int i = digits.length(); i < width; i++) b.append('0');
    return b.append(digits).toString();
  }
}
