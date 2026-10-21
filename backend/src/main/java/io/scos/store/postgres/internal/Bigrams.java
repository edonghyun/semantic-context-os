package io.scos.store.postgres.internal;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.IndexedText;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 키워드 어댑터의 순수 함수(SQL 없음): 색인 본문 정규화, 공백 제거 2-gram 계수, LIKE 이스케이프. 단위·변이 테스트 대상(TESTING §12 4순위).
 */
public final class Bigrams {
  private Bigrams() {}

  public static String normalize(IndexedText u) {
    return (u.title() + "\n" + u.content()).toLowerCase(Locale.ROOT);
  }

  /** 공백 제거 문자열의 2-gram → 등장 횟수(겹침 허용). 코드포인트 단위(아스트랄 문자 1자). */
  public static Map<String, Integer> bigramCounts(String s) {
    int[] cps = Text.stripWhitespace(s).codePoints().toArray();
    Map<String, Integer> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < cps.length; i++) out.merge(new String(cps, i, 2), 1, Integer::sum);
    return out;
  }

  /**
   * 코드포인트 하나를 PostgreSQL 정규식(ARE)의 리터럴로: ASCII 영숫자가 아닌 ASCII 문자는 역슬래시로 감싼다(ARE에서 "\\ + 비영숫자"는 그 문자
   * 자체).
   */
  public static String regexLiteral(int codePoint) {
    String c = Character.toString(codePoint);
    boolean asciiPunctuation = codePoint < 0x80 && !Character.isLetterOrDigit(codePoint);
    return asciiPunctuation ? "\\" + c : c;
  }

  public static String escapeLike(String t) {
    return t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
