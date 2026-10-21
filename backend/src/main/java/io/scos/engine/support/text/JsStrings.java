package io.scos.engine.support.text;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * JS 문자열 의미 중 커넥터가 TS 값과 맞춰야 하는 것: `String#trim`의 공백 집합(Java `\s`·{@code strip}과 다르다 —
 * NBSP·BOM·U+2028은 공백이고 NEL은 아니다)과 `Number(문자열)`(앞뒤 공백을 뗀 10진·0x·0b·0o·±Infinity, 빈 값은 0, 그 밖은
 * NaN). Drive 수집·투영이 함께 쓴다.
 */
public final class JsStrings {
  /** JS 공백(정규식 문자 클래스 안에 쓰는 형태). */
  public static final String WHITESPACE =
      "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

  private static final Pattern LEADING = Pattern.compile("^[" + WHITESPACE + "]+");
  private static final Pattern TRAILING = Pattern.compile("[" + WHITESPACE + "]+\\z");
  private static final Pattern DECIMAL =
      Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
  private static final Pattern RADIX = Pattern.compile("^0([xXbBoO])([0-9a-fA-F]+)$");

  private JsStrings() {}

  public static String trim(String s) {
    return trimEnd(LEADING.matcher(s).replaceFirst(""));
  }

  public static String trimEnd(String s) {
    return TRAILING.matcher(s).replaceFirst("");
  }

  /** JS {@code Number(s)}. */
  public static double number(String raw) {
    String s = trim(raw);
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
