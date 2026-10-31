package io.scos.client.redact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM 개인 키 블록을 BEGIN 줄부터 END 줄까지 통째로 가린다. END가 없으면(잘린 출력) 텍스트 끝까지다 — 키 본문이 어디서 끝나는지 알 수 없다. 정규식 재귀
 * 없이 표식만 찾아 선형이다.
 */
final class PrivateKeyBlockRule implements SecretRule {
  private static final Pattern BEGIN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
  private static final Pattern END = Pattern.compile("-----END [A-Z ]*PRIVATE KEY-----");

  @Override
  public String apply(String text) {
    Matcher begin = BEGIN.matcher(text);
    if (!begin.find()) {
      return text;
    }
    Matcher end = END.matcher(text);
    var masker = new Masker(text);
    int from = 0;
    while (from < text.length() && begin.find(from)) {
      int blockEnd = end.find(begin.end()) ? end.end() : text.length();
      masker.mask(begin.start(), blockEnd);
      from = blockEnd;
    }
    return masker.result();
  }
}
