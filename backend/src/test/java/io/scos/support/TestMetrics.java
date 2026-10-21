package io.scos.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.projection.ProjectionMetrics;
import io.scos.engine.internal.search.SearchMetrics;
import io.scos.engine.support.call.ProviderCallMetrics;

/**
 * 지표를 단언하지 않는 테스트용: 새 SimpleMeterRegistry 위의 지표 객체. 제품에는 지표 없는 생성 경로가 없다 — 지표를 보는 테스트는 레지스트리를 직접
 * 만들어 넘긴다.
 */
public final class TestMetrics {
  private TestMetrics() {}

  public static CollectionMetrics collection() {
    return CollectionMetrics.of(new SimpleMeterRegistry());
  }

  public static ProviderCallMetrics providerCalls() {
    return ProviderCallMetrics.of(new SimpleMeterRegistry());
  }

  public static ProjectionMetrics projection() {
    return ProjectionMetrics.of(new SimpleMeterRegistry());
  }

  public static SearchMetrics search() {
    return SearchMetrics.of(new SimpleMeterRegistry());
  }
}
