package io.scos.harness.evaluation.baseline;

import java.util.List;
import java.util.Map;

/** 카운트 내림차순으로 정렬하고 같은 카운트는 문서 id 오름차순으로 고정한다. */
final class RankedCounts {
  private RankedCounts() {}

  static List<String> descending(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .map(Map.Entry::getKey)
        .toList();
  }
}
