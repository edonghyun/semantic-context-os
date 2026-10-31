package io.scos.engine.internal.search.plan;

import io.scos.engine.Effort;
import io.scos.engine.InvalidQueryException;
import io.scos.engine.Profile;
import io.scos.engine.support.text.Text;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * (질의, 강도, 프로필, 시각)을 {@link RetrievalPlan}으로 옮긴다. 강도·프로필은 여기서만 해석한다. 최신성 표지 정규식은 기록된 계약과 같은 글자로
 * 둔다(NFKC·소문자 뒤 판정).
 */
public final class PlanResolver {
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

  /** search: AUTO는 질의 문구가 날짜 가산을 정한다. */
  public RetrievalPlan forSearch(String query, Effort effort, Profile profile, Instant now) {
    return plan(effort, recency(profile, prefersRecent(Text.nfkcLower(query), now)));
  }

  /** assemble: AUTO도 날짜 가산을 항상 켠다. */
  public RetrievalPlan forAssemble(String query, Effort effort, Profile profile, Instant now) {
    return plan(effort, recency(profile, true));
  }

  /** 상향: 계획이 정한 날짜 가산은 보존하고 강도 값만 {@code plan()}과 같은 방식으로 다시 푼다. */
  public RetrievalPlan escalate(RetrievalPlan plan, Effort to) {
    return plan(to, plan.recencyBoost());
  }

  private static RetrievalPlan plan(Effort effort, boolean recency) {
    return new RetrievalPlan(effort, Presets.of(effort), recency, Presets.rerank(effort));
  }

  private static boolean recency(Profile profile, boolean autoRecency) {
    return switch (profile) {
      case Profile.Auto a -> autoRecency;
      case Profile.Current c -> true;
      case Profile.AsOf a ->
          throw new InvalidQueryException("profile", "as-of is not supported yet");
    };
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
}
