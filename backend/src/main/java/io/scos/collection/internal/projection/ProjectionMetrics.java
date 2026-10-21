package io.scos.collection.internal.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.scos.engine.support.projection.EmbeddingCounts;

/**
 * 투영 지표(ADR-0009): run 시간(outcome), 문서 결과(ok | failed + reason), 임베딩 입력 출처(cache | embedder). 요청 큐
 * 게이지는 데몬 조립의 DaemonGauges가 등록한다.
 */
public final class ProjectionMetrics {
  public static final String RUN = "scos.projection.run";
  public static final String DOCUMENTS = "scos.projection.documents";

  /** 벡터를 얻은 임베딩 입력 수(source = cache | embedder) — 재수집의 임베딩 캐시 적중률(ADR-0026). */
  public static final String EMBEDDING_INPUTS = "scos.projection.embedding.inputs";

  private final MeterRegistry registry;

  private ProjectionMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public static ProjectionMetrics of(MeterRegistry registry) {
    return new ProjectionMetrics(registry);
  }

  public MeterRegistry registry() {
    return registry;
  }

  public Timer run(ProjectionRun.Outcome outcome) {
    return Timer.builder(RUN).tag("outcome", outcome.wire()).register(registry);
  }

  public void documents(int ok, java.util.List<ProjectionRun.DocumentFailure> failures) {
    if (ok > 0)
      Counter.builder(DOCUMENTS)
          .tag("outcome", "ok")
          .tag("reason", "none")
          .register(registry)
          .increment(ok);
    for (ProjectionRun.DocumentFailure f : failures)
      Counter.builder(DOCUMENTS)
          .tag("outcome", "failed")
          .tag("reason", f.reason())
          .register(registry)
          .increment();
  }

  /** 임베딩 입력을 출처별로 센다. 0인 출처는 카운터를 만들지 않는다. */
  public void embeddings(EmbeddingCounts counts) {
    if (counts.fromCache() > 0)
      Counter.builder(EMBEDDING_INPUTS)
          .tag("source", "cache")
          .register(registry)
          .increment(counts.fromCache());
    if (counts.embedded() > 0)
      Counter.builder(EMBEDDING_INPUTS)
          .tag("source", "embedder")
          .register(registry)
          .increment(counts.embedded());
  }
}
