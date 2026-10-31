package io.scos.harness.evaluation;

import io.scos.harness.perf.LatencyBenchmark.Percentiles;
import java.util.List;
import java.util.Optional;

/** 호출 지연(ms)의 중앙값과 p95(nearest-rank). 측정이 없으면 요약도 없다. */
public record LatencySummary(double median, double p95) {
  public static Optional<LatencySummary> of(List<Double> ms) {
    if (ms.isEmpty()) return Optional.empty();
    Percentiles p = Percentiles.of(ms);
    return Optional.of(new LatencySummary(p.p50(), p.p95()));
  }
}
