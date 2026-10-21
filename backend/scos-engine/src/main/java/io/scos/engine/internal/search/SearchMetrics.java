package io.scos.engine.internal.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;

/** 검색 지표(ADR-0009): 단계별 시간(TS StageTrace 대응, recipe·stage 태그)과 재정렬 결과 수. 질의 원문은 태그에 넣지 않는다. */
public final class SearchMetrics {
  public static final String STAGE = "scos.search.stage";
  public static final String RERANK = "scos.search.rerank";

  private final MeterRegistry registry;

  private SearchMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public static SearchMetrics of(MeterRegistry registry) {
    return new SearchMetrics(registry);
  }

  public MeterRegistry registry() {
    return registry;
  }

  <T> T stage(String recipe, String stage, Supplier<T> body) {
    return Timer.builder(STAGE)
        .tag("recipe", recipe)
        .tag("stage", stage)
        .register(registry)
        .record(body);
  }

  void rerank(RerankOutcome outcome) {
    String kind =
        switch (outcome) {
          case RerankOutcome.Applied a -> "applied";
          case RerankOutcome.FallbackError e -> "fallback_error";
          case RerankOutcome.FallbackFormat f -> "fallback_format";
          case RerankOutcome.Skipped s -> "skipped";
        };
    Counter.builder(RERANK).tag("outcome", kind).register(registry).increment();
  }
}
