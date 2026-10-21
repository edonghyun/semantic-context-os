package io.scos.collection.internal.projection;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 투영의 점유 heartbeat도 수집과 같은 지표(scos.collection.lease_heartbeat)를 앱 레지스트리에 남긴다(ADR-0020 §3). */
@DisplayName("투영의 점유 heartbeat도 수집과 같은 지표를 앱 레지스트리에 남긴다(ADR-0020 §3)")
class ProjectionHeartbeatMetricsTest {
  /** 대역 임베더 차원(값은 결과에 영향 없음). */
  static final int EMBEDDING_DIM = 2;

  /** 임베딩 한 번 사이에 흐르는 시간 — heartbeat tick 한 번을 만든다. */
  static final Duration HEARTBEAT_INTERVAL_ELAPSED = Duration.ofSeconds(10);

  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  MemoryProjection fixture = new MemoryProjection();

  /** 임베딩마다 heartbeat를 한 번 돌리는 임베더. */
  Embedder tickingEmbedder() {
    return new Embedder() {
      @Override
      public int dim() {
        return EMBEDDING_DIM;
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        fixture.clock.advance(HEARTBEAT_INTERVAL_ELAPSED);
        fixture.scheduler.tick();
        return FixedEmbedder.zero(EMBEDDING_DIM).embed(texts);
      }
    };
  }

  @Test
  @DisplayName("투영 중 heartbeat tick은 앱 레지스트리에 outcome=ok로 센다")
  void projectionHeartbeatIsCounted() {
    // given
    SourceInstanceId source = fixture.slackSourceWithOneMessage("slack-a", "결제 장애 회고");

    // when
    List<ProjectionRun.Report> reports =
        fixture.projection(tickingEmbedder(), registry).runDue(CancellationToken.none());

    // then: tick이 센 것은 끝까지 간 투영의 heartbeat다
    if (reports.size() != 1
        || !reports.getFirst().source().equals(source)
        || reports.getFirst().outcome() != ProjectionRun.Outcome.SUCCEEDED)
      throw new IllegalStateException("given 준비 실패: 투영이 끝까지 가야 한다 — " + reports);
    assertThatMetrics(registry).hasCounter(CollectionMetrics.LEASE_HEARTBEAT, "outcome", "ok", 1);
  }
}
