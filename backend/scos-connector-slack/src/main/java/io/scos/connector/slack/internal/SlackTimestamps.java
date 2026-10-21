package io.scos.connector.slack.internal;

import io.scos.spi.connector.SourceOrder;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack ts("초.마이크로초") 유틸(TS slack-message.ts): 마이크로초 BigInteger, 순서 스킴 `slack.message-state.v1`.
 */
public final class SlackTimestamps {
  static final String ORDER_SCHEME = "slack.message-state.v1";
  private static final Pattern TS = Pattern.compile("^(\\d{1,20})\\.(\\d{1,6})$");

  private SlackTimestamps() {}

  public static Optional<BigInteger> micros(String value) {
    Matcher m = TS.matcher(value);
    if (!m.matches()) return Optional.empty();
    return Optional.of(
        new BigInteger(m.group(1))
            .multiply(BigInteger.valueOf(1_000_000))
            .add(new BigInteger(padEnd(m.group(2)))));
  }

  public static boolean isValid(String value) {
    return micros(value).isPresent();
  }

  public static SourceOrder order(String timestamp) {
    Matcher m = TS.matcher(timestamp);
    if (!m.matches()) throw new IllegalArgumentException("Invalid Slack timestamp");
    return new SourceOrder(ORDER_SCHEME, padStart(m.group(1)) + "." + padEnd(m.group(2)));
  }

  /** 마이크로초 → "초.마이크로초(6자리)". */
  public static String format(BigInteger micros) {
    BigInteger[] qr = micros.divideAndRemainder(BigInteger.valueOf(1_000_000));
    return qr[0] + "." + padStart6(qr[1].toString());
  }

  /** 둘 중 뒤의 시각. 빈 문자열은 "없음". */
  public static String later(String current, String candidate) {
    if (candidate.isEmpty()) return current;
    if (current.isEmpty()) return candidate;
    BigInteger c =
        micros(current).orElseThrow(() -> new IllegalArgumentException("Invalid Slack timestamp"));
    BigInteger n =
        micros(candidate)
            .orElseThrow(() -> new IllegalArgumentException("Invalid Slack timestamp"));
    return n.compareTo(c) > 0 ? candidate : current;
  }

  private static String padEnd(String fraction) {
    StringBuilder b = new StringBuilder(fraction);
    while (b.length() < 6) b.append('0');
    return b.toString();
  }

  private static String padStart(String seconds) {
    StringBuilder b = new StringBuilder();
    for (int i = seconds.length(); i < 20; i++) b.append('0');
    return b.append(seconds).toString();
  }

  private static String padStart6(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = s.length(); i < 6; i++) b.append('0');
    return b.append(s).toString();
  }
}
