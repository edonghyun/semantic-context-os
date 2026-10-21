package io.scos.engine.support.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 문자열 결정론(CONTRACTS F-1·F-3): TS와 같은 의미의 공백 분리·정규화·해시를 한곳에 둔다. 어댑터·레시피는 `\\s`를 직접 쓰지 않는다. JS `\s`는
 * 유니코드 공백(U+00A0·U+3000·U+FEFF 등)을 포함하고 Java `\s`는 ASCII뿐이다(리뷰 I-4, RED: "결제 오류　대응"이 한 토큰) — JS
 * 클래스를 명시한다.
 */
public final class Text {
  /**
   * JS `\s` = [\t\n\v\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]
   * (ECMAScript WhiteSpace + LineTerminator).
   */
  static final String JS_WHITESPACE =
      "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";

  private static final Pattern WHITESPACE = Pattern.compile(JS_WHITESPACE + "+");
  private static final Pattern LEADING = Pattern.compile("^" + JS_WHITESPACE + "+");
  private static final Pattern TRAILING = Pattern.compile(JS_WHITESPACE + "+\\z");

  /** 정규식 조각으로 쓸 때(`\\s*` 대신). */
  public static String whitespaceClass() {
    return JS_WHITESPACE;
  }

  /** 문자 클래스 안에 넣을 때(`[^)\\s]` 같은 부정 클래스): 대괄호를 뺀 내용. */
  public static String whitespaceChars() {
    return JS_WHITESPACE.substring(1, JS_WHITESPACE.length() - 1);
  }

  /** TS `String.prototype.trimEnd()` — JS 공백 집합(NBSP·U+3000·BOM 포함; Java `strip`은 NBSP를 남긴다). */
  public static String jsTrimEnd(String s) {
    return TRAILING.matcher(s).replaceFirst("");
  }

  /** TS `String.prototype.trim()`. */
  public static String jsTrim(String s) {
    return LEADING.matcher(jsTrimEnd(s)).replaceFirst("");
  }

  private Text() {}

  /** TS `text.split(/\s+/).filter(Boolean)`: 공백 분리, 빈 조각 제거. */
  public static List<String> splitWhitespace(String s) {
    List<String> out = new ArrayList<>();
    for (String t : WHITESPACE.split(s, -1)) if (!t.isEmpty()) out.add(t);
    return out;
  }

  /** TS `replace(/\s+/g, "")`. */
  public static String stripWhitespace(String s) {
    return WHITESPACE.matcher(s).replaceAll("");
  }

  /** TS `normalize("NFKC").toLowerCase()`(로케일 무관). */
  public static String nfkcLower(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  /** 토큰 안의 문자 3-gram(코드포인트, 토큰 경계를 넘지 않음) — TS compact 경로·LexicalAffinity와 같다. 3글자 미만이면 빈 목록. */
  public static List<String> trigrams(String token) {
    int[] cps = token.codePoints().toArray();
    List<String> out = new ArrayList<>();
    for (int i = 0; i + 2 < cps.length; i++) out.add(new String(cps, i, 3));
    return out;
  }

  /** TS `sha256(utf8)` hex. */
  public static String sha256Hex(String s) {
    return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
