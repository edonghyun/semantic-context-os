package io.scos.collection.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.spi.connector.ReconciliationKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("재조정 지표는 영속 보고의 발견 상태 수를 반영한다")
class ReconciliationMetricsTest {
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final CollectionMetrics metrics = CollectionMetrics.of(registry);
  static final Tags PARTIAL_MISS =
      Tags.of(
          "path",
          "full",
          "classification",
          "incremental_miss",
          "reason",
          "none",
          "kind",
          "page",
          "result",
          "partial");

  @Test
  @DisplayName("차이 없는 eligible 표본도 분모와 누락 0을 함께 보고한다")
  void reportsZeroMissWithEligibleDenominator() {
    // given
    var summary = new ReconciliationDiscovery.Summary(2, false, List.of());
    // when
    metrics.reconciliation(summary);
    // then
    assertThatMetrics(registry)
        .hasCounter(
            CollectionMetrics.RECONCILIATION_ELIGIBLE,
            Tags.of("path", "full", "kind", "page", "result", "complete"),
            2);
    assertThatMetrics(registry)
        .hasCounter(
            CollectionMetrics.RECONCILIATION_DISCOVERED,
            Tags.of(
                "path",
                "full",
                "classification",
                "incremental_miss",
                "reason",
                "none",
                "kind",
                "page",
                "result",
                "complete"),
            0);
  }

  @Test
  @DisplayName("누락 상태 수와 partial 구분을 유한 태그로 보고한다")
  void reportsMissWithLowCardinalityTags() {
    // given
    var summary =
        new ReconciliationDiscovery.Summary(
            2,
            true,
            List.of(
                new ReconciliationDiscovery.Count(
                    ReconciliationKind.PAGE,
                    ReconciliationDiscovery.Classification.INCREMENTAL_MISS,
                    1)));
    // when
    metrics.reconciliation(summary);
    // then
    assertThatMetrics(registry)
        .hasCounter(CollectionMetrics.RECONCILIATION_DISCOVERED, PARTIAL_MISS, 1);
  }
}
