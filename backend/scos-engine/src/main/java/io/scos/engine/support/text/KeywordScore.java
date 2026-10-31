package io.scos.engine.support.text;

import java.util.List;

/**
 * SQLite와 메모리 키워드 색인이 공유하는 부분 문자열·trigram 점수 정책. 토큰별 합이며 토큰은 소문자·공백 없음을 전제한다. 3글자 이상: {@code text}에
 * 정확 부분 문자열이면 {@code 0.5 + 0.5 * c/(1+c)}, 아니고 토큰의 trigram 하나라도 {@code compact}에 있으면 0.25. 3글자 미만:
 * {@code c/(1+c)}. c는 {@code text}의 겹치지 않는 등장 횟수.
 */
public final class KeywordScore {
  private final List<String> tokens;

  private KeywordScore(List<String> tokens) {
    this.tokens = tokens;
  }

  public static KeywordScore of(List<String> tokens) {
    return new KeywordScore(List.copyOf(tokens));
  }

  public double score(String text, String compact) {
    double sum = 0;
    for (String token : tokens) sum += tokenScore(token, text, compact);
    return sum;
  }

  private static double tokenScore(String token, String text, String compact) {
    if (token.isEmpty()) return 0;
    int c = occurrences(text, token);
    if (token.codePointCount(0, token.length()) < 3) return c / (1.0 + c);
    if (c > 0) return 0.5 + 0.5 * (c / (1.0 + c));
    return Text.trigrams(token).stream().anyMatch(compact::contains) ? 0.25 : 0;
  }

  private static int occurrences(String text, String token) {
    int count = 0;
    for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length()))
      count++;
    return count;
  }
}
