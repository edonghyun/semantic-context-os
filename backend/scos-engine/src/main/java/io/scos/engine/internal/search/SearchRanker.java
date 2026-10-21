package io.scos.engine.internal.search;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import io.scos.engine.support.text.Text;
import io.scos.spi.store.Tier;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 최종 랭커(TS SearchRanker + DefaultRanker). 관련도가 기본이고, 질의에 최신성 표지가 있을 때만 날짜 가중(1+0.1·0.99^days,
 * compacted 동점 +0.1%)을 적용한다. 정규식은 TS와 같다(NFKC·소문자 뒤 판정). Math.pow 경로는 CONTRACTS F-7 1e-12 정책.
 */
final class SearchRanker {
  private static final Pattern PAST =
      Pattern.compile("(?:19|20)\\d{2}|작년|재작년|지난|과거|당시|예전|\\b(?:previous|historical|last)\\b");
  private static final String WS = Text.whitespaceClass();
  private static final Pattern MONTH = Pattern.compile("(\\d{1,2})" + WS + "*월");
  private static final Pattern DAY =
      Pattern.compile(
          "\\d{1,2}" + WS + "*월" + WS + "*\\d{1,2}" + WS + "*일|\\b\\d{1,2}[-/.]\\d{1,2}\\b");
  private static final Pattern RECENT =
      Pattern.compile(
          "최근|최신|현재|요즘|지금|오늘|이번"
              + WS
              + "*(?:주|달|월|분기|해)(?="
              + WS
              + "|$|의|에|은|는)|\\b(?:latest|recent|current|today)\\b|\\bthis"
              + WS
              + "+(?:week|month|quarter|year)\\b");
  private static final Pattern HOWTO =
      Pattern.compile("방법|양식|템플릿|작성법|가이드|정의|절차|설정|\\b(?:template|guide|how)\\b");
  private static final Pattern PERIODIC =
      Pattern.compile(
          "(?:일간|주간|월간)"
              + WS
              + "*(?:보고|리포트|지표|현황|회의록)|\\b(?:daily|weekly|monthly)"
              + WS
              + "+(?:report|metrics|status|update|meeting notes)\\b");

  List<Candidate> rank(String query, List<Candidate> candidates, Instant now) {
    String q = Text.nfkcLower(query);
    List<Candidate> out =
        new ArrayList<>(prefersRecent(q, now) ? withFreshness(candidates, now) : candidates);
    out.sort((a, b) -> Double.compare(b.baseScore(), a.baseScore()));
    return out;
  }

  static boolean prefersRecent(String query, Instant now) {
    if (PAST.matcher(query).find()) return false;
    int currentMonth = now.atZone(java.time.ZoneOffset.UTC).getMonthValue();
    Matcher m = MONTH.matcher(query);
    while (m.find()) if (Integer.parseInt(m.group(1)) != currentMonth) return false;
    if (DAY.matcher(query).find()) return false;
    if (RECENT.matcher(query).find()) return true;
    if (HOWTO.matcher(query).find()) return false;
    return PERIODIC.matcher(query).find();
  }

  /** TS DefaultRanker: 1 + 0.1·0.99^days (CONTRACTS F-7 Math.pow 경로, 상대 오차 1e-12). */
  static double freshnessFactor(double days) {
    return 1 + 0.1 * Math.pow(0.99, days);
  }

  private static List<Candidate> withFreshness(List<Candidate> candidates, Instant now) {
    List<Candidate> out = new ArrayList<>(candidates.size());
    for (Candidate c : candidates) {
      double freshness = 1;
      try {
        double days =
            Math.max(
                0,
                (now.toEpochMilli()
                        - Instant.parse(c.unit().provenance().capturedAt()).toEpochMilli())
                    / 86_400_000.0);
        freshness = freshnessFactor(days);
      } catch (DateTimeParseException e) {
        // 날짜가 없거나 잘못된 유닛은 가산하지 않는다(TS와 같다)
      }
      double tieBreak = c.unit().tier() == Tier.COMPACTED ? c.baseScore() * 0.001 : 0;
      out.add(new Candidate(c.unit(), c.baseScore() * freshness + tieBreak));
    }
    return out;
  }
}
