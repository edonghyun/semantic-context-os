package io.scos.engine.internal.search.signal;

import io.scos.engine.RankingSettings;
import io.scos.engine.internal.search.Candidate;
import io.scos.engine.internal.search.CapturedAt;
import io.scos.engine.internal.search.plan.RetrievalPlan;
import java.time.Instant;

/**
 * 날짜 가중: 1 + weight·retention^days(days = 수집 뒤 지난 일수, 0 아래로는 가지 않는다). 기본은 1 + 0.1·0.99^days. 수집 시각이
 * 없거나 잘못된 유닛은 1.0이다. Math.pow 경로는 CONTRACTS F-7 1e-12 정책.
 */
public final class RecencySignal implements Signal {
  private static final double MILLIS_PER_DAY = 86_400_000.0;
  private final double weight;
  private final double retention;

  public RecencySignal() {
    this(RankingSettings.Recency.DEFAULT);
  }

  public RecencySignal(RankingSettings.Recency recency) {
    this.weight = recency.weight();
    this.retention = recency.dailyRetention();
  }

  /** 1 + weight·retention^days. */
  public double factor(double days) {
    return 1 + weight * Math.pow(retention, days);
  }

  @Override
  public double factor(Candidate c, RetrievalPlan plan, Instant now) {
    return new CapturedAt(c.unit().provenance().capturedAt())
        .instant()
        .map(
            captured ->
                factor(
                    Math.max(0, (now.toEpochMilli() - captured.toEpochMilli()) / MILLIS_PER_DAY)))
        .orElse(1.0);
  }
}
