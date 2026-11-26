package io.scos.engine.internal.search.plan;

import io.scos.engine.Effort;
import io.scos.engine.InvalidQueryException;
import io.scos.engine.Profile;
import io.scos.engine.RankingSettings;
import io.scos.engine.RankingSettings.Recency.When;
import io.scos.engine.support.text.Text;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * (질의, 강도, 프로필, 시각)을 {@link RetrievalPlan}으로 옮긴다. 강도·프로필은 여기서만 해석한다. 최신성 표지 정규식은 기록된 계약과 같은 글자로
 * 둔다(NFKC·소문자 뒤 판정). 인스턴스 순위 값(날짜 계수 적용 조건·문서당 상한·원천 별명)도 여기서 계획에 싣는다(CONTRACTS B13).
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

  private final RankingSettings ranking;

  public PlanResolver() {
    this(RankingSettings.DEFAULT);
  }

  public PlanResolver(RankingSettings ranking) {
    this.ranking = ranking;
  }

  /** search: AUTO는 적용 조건이 MARKED면 질의 문구가, ALWAYS면 언제나 날짜 가산을 켠다. */
  public RetrievalPlan forSearch(String query, Effort effort, Profile profile, Instant now) {
    boolean auto =
        ranking.recency().when() == When.ALWAYS || prefersRecent(Text.nfkcLower(query), now);
    return plan(effort, recency(profile, auto), Mentions.of(ranking.sources(), query));
  }

  /** assemble: AUTO도 날짜 가산을 항상 켠다. */
  public RetrievalPlan forAssemble(String query, Effort effort, Profile profile, Instant now) {
    return plan(effort, recency(profile, true), Mentions.of(ranking.sources(), query));
  }

  /** 상향: 계획이 정한 날짜 가산·원천은 보존하고 강도 값만 {@code plan()}과 같은 방식으로 다시 푼다. */
  public RetrievalPlan escalate(RetrievalPlan plan, Effort to) {
    return plan(to, plan.recencyBoost(), plan.mentions());
  }

  private RetrievalPlan plan(Effort effort, boolean recency, Mentions mentions) {
    SearchSettings settings = Presets.of(effort).withPerDocument(ranking.perDocument());
    return new RetrievalPlan(effort, settings, recency, Presets.rerank(effort), mentions);
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
