package io.scos.collection.internal.credentials;

/**
 * 자격 증명 값의 앞뒤 공백 떼기 — TS `String.prototype.trim()`과 같은 문자 집합(ECMAScript WhiteSpace +
 * LineTerminator). Java `strip()`과 다르다: BOM(U+FEFF)·NBSP(U+00A0)·U+202F 등을 떼고, 정보
 * 분리자(U+001C–U+001F)는 남긴다. 메모장이 붙인 BOM이 있는 토큰 파일도 TS처럼 풀린다.
 */
final class CredentialText {
  private CredentialText() {}

  static String trim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isJsWhitespace(value.charAt(start))) start++;
    while (end > start && isJsWhitespace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  static boolean isJsWhitespace(char c) {
    return switch (c) {
      case '\t',
          '\n',
          '\u000B',
          '\f',
          '\r',
          ' ',
          '\u00A0',
          '\u1680',
          '\u2028',
          '\u2029',
          '\u202F',
          '\u205F',
          '\u3000',
          '\uFEFF' ->
          true;
      default -> c >= '\u2000' && c <= '\u200A';
    };
  }
}
