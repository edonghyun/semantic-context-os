package io.scos.collection.internal.projection;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.engine.support.projection.EmbeddingCounts;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 투영 지표(ADR-0009)의 등록 규칙: 결과별 타이머, 문서 ok·failed(reason) 카운터, ok 0건은 카운터를 만들지 않는다. */
@DisplayName("투영 지표는 결과별 타이머와 문서·임베딩 입력 카운터를 남긴다(ADR-0009)")
class ProjectionMetricsTest {
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  ProjectionMetrics metrics = ProjectionMetrics.of(registry);

  void recordRun(ProjectionRun.Outcome outcome) {
    metrics.run(outcome).record(1, TimeUnit.MILLISECONDS);
  }

  static Tags documents(String outcome, String reason) {
    return Tags.of("outcome", outcome, "reason", reason);
  }

  @Test
  @DisplayName("run(outcome)은 결과마다 따로 run 타이머에 기록된다")
  void runTimerPerOutcome() {
    // when
    recordRun(ProjectionRun.Outcome.SUCCEEDED);
    recordRun(ProjectionRun.Outcome.SUCCEEDED);
    recordRun(ProjectionRun.Outcome.FAILED);

    // then
    assertThatMetrics(registry)
        .hasTimerCount(ProjectionMetrics.RUN, Tags.of("outcome", "succeeded"), 2)
        .hasTimerCount(ProjectionMetrics.RUN, Tags.of("outcome", "failed"), 1);
  }

  @Test
  @DisplayName("documents는 ok 수와 실패 사유별 수를 센다")
  void documentsCountOkAndFailuresByReason() {
    // when
    metrics.documents(
        3,
        List.of(
            new ProjectionRun.DocumentFailure("d1", "embed", "rate_limited"),
            new ProjectionRun.DocumentFailure("d2", "store", "claim_lost")));

    // then
    assertThatMetrics(registry)
        .hasCounter(ProjectionMetrics.DOCUMENTS, documents("ok", "none"), 3)
        .hasCounter(ProjectionMetrics.DOCUMENTS, documents("failed", "rate_limited"), 1)
        .hasCounter(ProjectionMetrics.DOCUMENTS, documents("failed", "claim_lost"), 1);
  }

  @Test
  @DisplayName("문서가 0건이면 카운터를 만들지 않는다")
  void zeroDocumentsRegistersNothing() {
    // when
    metrics.documents(0, List.of());

    // then
    assertThatMetrics(registry).hasNoMeter(ProjectionMetrics.DOCUMENTS);
  }

  @Test
  @DisplayName("of(registry).registry()는 받은 레지스트리를 그대로 돌려준다")
  void registryIsShared() {
    // when·then
    assertThat(metrics.registry()).isSameAs(registry);
  }

  @Test
  @DisplayName("embeddings는 임베딩 입력을 출처별로 센다")
  void countsEmbeddingInputsBySource() {
    // when
    metrics.embeddings(new EmbeddingCounts(5, 2));

    // then
    assertThatMetrics(registry)
        .hasCounter(ProjectionMetrics.EMBEDDING_INPUTS, "source", "cache", 5)
        .hasCounter(ProjectionMetrics.EMBEDDING_INPUTS, "source", "embedder", 2);
  }

  @Test
  @DisplayName("캐시 적중이 0이면 cache 카운터를 만들지 않는다")
  void skipsZeroCacheHits() {
    // when
    metrics.embeddings(new EmbeddingCounts(0, 4));

    // then
    assertThatMetrics(registry).hasNoCounter(ProjectionMetrics.EMBEDDING_INPUTS, "source", "cache");
  }

  @Test
  @DisplayName("임베딩이 0이면 embedder 카운터를 만들지 않는다")
  void skipsZeroSource() {
    // when
    metrics.embeddings(new EmbeddingCounts(3, 0));

    // then
    assertThatMetrics(registry)
        .hasNoCounter(ProjectionMetrics.EMBEDDING_INPUTS, "source", "embedder");
  }
}
