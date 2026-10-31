package io.scos.collection.internal;

import io.scos.spi.access.SourceFreshness;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 수집 이력의 본문 단계 성공 시각을 엔진 포트로 제공한다. */
public final class RunHistoryFreshness implements SourceFreshness {
  private final RunStore runs;

  public RunHistoryFreshness(RunStore runs) {
    this.runs = runs;
  }

  @Override
  public Map<SourceInstanceId, Instant> lastSucceeded(Set<SourceInstanceId> instances) {
    return runs.lastSucceededAt(instances);
  }
}
