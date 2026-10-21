package io.scos.engine.support.text;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * TS `JSON.stringify`와 바이트 단위로 같은 직렬화(공백 없음). 커넥터 원본의 sourceVersion은 TS에서 이 문자열의
 * sha256이라(Jira·Tiro), 같은 응답이면 TS와 같은 버전이 나와야 한다(F12 저장 호환 — 이전한 원본이 다시 수집해도 unchanged).
 * Jira·Tiro·질의 로그·구조화 로그가 함께 쓴다. 키 순서는 JS 객체 순회 순서({@link #entries}), 문자열 이스케이프는 `"`·`\`·제어 문자(짧은
 * 형식 또는 소문자 `\\u00xx`)·홀로 남은 서로게이트만(비ASCII는 그대로), 수는 JS `Number#toString` — 안전 정수 범위 밖 정수와 소수는
 * 배정밀도로 읽은 값의 최단 표기(`1e-7`, `1e+21`), `-0`은 `0`.
 */
public final class JsJson {
  /** 2^53 − 1: JS가 정수를 정확히 담는 상한. */
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private static final int EXPONENT_FROM = 21;
  private static final int DECIMAL_FROM = -6;

  /** 배열 인덱스 키의 표기: 0 또는 0으로 시작하지 않는 10자리 이하 정수. */
  private static final Pattern ARRAY_INDEX = Pattern.compile("^(0|[1-9]\\d{0,9})$");

  /** 2^32 − 2: JS 배열 인덱스의 상한. */
  private static final long MAX_ARRAY_INDEX = 4_294_967_294L;

  private JsJson() {}

  /**
   * JS 객체의 키 순회 순서(`JSON.parse`가 만든 객체의 `JSON.stringify`·`Object.entries`): 배열 인덱스 키를 오름차순으로 먼저,
   * 나머지는 받은 순서.
   */
  public static List<Map.Entry<String, JsonNode>> entries(JsonNode object) {
    List<Map.Entry<String, JsonNode>> indexKeys = new ArrayList<>();
    List<Map.Entry<String, JsonNode>> others = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : object.properties())
      (isArrayIndex(e.getKey()) ? indexKeys : others).add(e);
    indexKeys.sort(Comparator.comparingLong(e -> Long.parseLong(e.getKey())));
    List<Map.Entry<String, JsonNode>> out = new ArrayList<>(indexKeys);
    out.addAll(others);
    return out;
  }

  private static boolean isArrayIndex(String key) {
    return ARRAY_INDEX.matcher(key).matches() && Long.parseLong(key) <= MAX_ARRAY_INDEX;
  }

  public static String stringify(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  /**
   * TS {@code JSON.stringify(값, null, 2)}: 들여쓰기 2칸, 빈 객체·배열은 {@code {}}·{@code []}, 키 뒤 {@code ":
   * "}.
   */
  public static String pretty(JsonNode node) {
    StringBuilder out = new StringBuilder();
    writePretty(node, out, "");
    return out.toString();
  }

  private static void writePretty(JsonNode node, StringBuilder out, String indent) {
    String inner = indent + "  ";
    if (node.isObject() && !node.isEmpty()) {
      out.append("{\n");
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : entries(node)) {
        if (!first) out.append(",\n");
        first = false;
        out.append(inner).append(string(e.getKey())).append(": ");
        writePretty(e.getValue(), out, inner);
      }
      out.append('\n').append(indent).append('}');
    } else if (node.isArray() && !node.isEmpty()) {
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(",\n");
        out.append(inner);
        writePretty(node.get(i), out, inner);
      }
      out.append('\n').append(indent).append(']');
    } else write(node, out);
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : entries(node)) {
        if (!first) out.append(',');
        first = false;
        out.append(string(e.getKey())).append(':');
        write(e.getValue(), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        write(node.get(i), out);
      }
      out.append(']');
    } else if (node.isString()) out.append(string(node.asString()));
    else if (node.isNumber()) out.append(number(node));
    else if (node.isBoolean()) out.append(node.asBoolean());
    else out.append("null");
  }

  /** JSON.stringify의 문자열 이스케이프. */
  public static String string(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || loneSurrogate(s, i)) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }

  private static boolean loneSurrogate(String s, int i) {
    char c = s.charAt(i);
    if (Character.isHighSurrogate(c))
      return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
    return Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
  }

  /** JS가 JSON을 읽어 다시 쓴 수: 배정밀도로 읽고(안전 정수 범위 밖 정수는 반올림된다) Number#toString으로 쓴다. */
  static String number(JsonNode node) {
    return number(node.asDouble());
  }

  /**
   * 값으로 되읽히는 가장 짧은 십진수(JS Number::toString의 자릿수). {@code Double.toString}은 한 자리로 충분해도 두 자리 가운데 가장
   * 가까운 것을 고른다(JDK 19+) — 정규 double에서는 같은 값이 되지만 비정규 수(5e-324는 "4.9E-324")에서 갈린다. 그래서 두 자리면 한 자리
   * 후보(정확한 값을 한 자리로 반올림)가 되읽히는지 본다.
   */
  private static BigDecimal shortestDigits(double value) {
    BigDecimal shortest = new BigDecimal(Double.toString(value)).stripTrailingZeros();
    if (shortest.precision() != 2) return shortest;
    BigDecimal oneDigit = new BigDecimal(value).round(new MathContext(1, RoundingMode.HALF_EVEN));
    return oneDigit.doubleValue() == value ? oneDigit.stripTrailingZeros() : shortest;
  }

  /** ECMAScript Number::toString(10): 최단 자릿수 s(k자리)와 지수 n으로 고정·소수·지수 표기를 고른다. */
  public static String number(double value) {
    if (value < 0) return "-" + number(-value); // -0은 음수가 아니라 "0"이 된다
    BigDecimal shortest = shortestDigits(value);
    String digits = shortest.unscaledValue().toString();
    int k = digits.length();
    int n = k - shortest.scale();
    if (n > EXPONENT_FROM || n <= DECIMAL_FROM) {
      String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
      return mantissa + "e" + String.format(Locale.ROOT, "%+d", n - 1);
    }
    if (n >= k) return digits + "0".repeat(n - k);
    if (n > 0) return digits.substring(0, n) + "." + digits.substring(n);
    return "0." + "0".repeat(-n) + digits;
  }
}
