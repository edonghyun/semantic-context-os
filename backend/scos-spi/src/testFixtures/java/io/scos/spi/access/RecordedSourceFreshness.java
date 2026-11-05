package io.scos.spi.access;

import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 엔진 테스트가 수집 완료를 기록하는 원천 최신성 참조 구현. */
public final class RecordedSourceFreshness implements SourceFreshness {
  private final Map<SourceInstanceId, Instant> succeeded = new HashMap<>();

  public void recordSuccess(SourceInstanceId instance, Instant finishedAt) {
    succeeded.merge(instance, finishedAt, (a, b) -> a.isAfter(b) ? a : b);
  }

  public void recordNonSuccess(SourceInstanceId instance, Instant finishedAt) {}

  @Override
  public Map<SourceInstanceId, Instant> lastSucceeded(Set<SourceInstanceId> instances) {
    Map<SourceInstanceId, Instant> requested = new HashMap<>();
    for (SourceInstanceId instance : instances) {
      Instant at = succeeded.get(instance);
      if (at != null) requested.put(instance, at);
    }
    return Map.copyOf(requested);
  }
}
