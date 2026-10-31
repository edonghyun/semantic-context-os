package io.scos.engine.support.text;

import io.scos.engine.support.PureFunctions;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * JS `Number(문자열)`: 앞뒤 JS 공백({@link Text#jsTrim})을 뗀 10진·0x·0b·0o·±Infinity, 빈 값은 0, 그 밖은 NaN.
 * Drive 수집·투영과 Retry-After 읽기가 함께 쓴다.
 */
@PureFunctions
public final class JsStrings {
  private static final Pattern DECIMAL =
      Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
  private static final Pattern RADIX = Pattern.compile("^0([xXbBoO])([0-9a-fA-F]+)$");

  private JsStrings() {}

  /** JS {@code Number(s)}. */
  public static double number(String raw) {
    String s = Text.jsTrim(raw);
    if (s.isEmpty()) return 0;
    if (DECIMAL.matcher(s).matches()) return Double.parseDouble(s);
    var radix = RADIX.matcher(s);
    if (radix.matches()) {
      int base =
          switch (Character.toLowerCase(radix.group(1).charAt(0))) {
            case 'x' -> 16;
            case 'b' -> 2;
            default -> 8;
          };
      try {
        return new BigInteger(radix.group(2), base).doubleValue();
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
    return switch (s) {
      case "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY;
      case "-Infinity" -> Double.NEGATIVE_INFINITY;
      default -> Double.NaN;
    };
  }
}
