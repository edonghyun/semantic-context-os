package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.embedding.FixedEmbedder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 투영의 문서 쓰기는 작업 진행 신호를 남긴다 — 긴 초기 투영이 데몬 health에서 정체로 보이지 않는다(ADR-0020 §3). */
@DisplayName("투영의 문서 쓰기는 작업 진행 신호를 남긴다(ADR-0020 §3)")
class ProjectionProgressTest {
  static final int EMBEDDING_DIM = 2;
  MemoryProjection fixture = new MemoryProjection();

  @Test
  @DisplayName("투영이 문서를 쓰면 작업 진행 신호를 그 시각으로 남긴다")
  void documentWriteMarksProgress() {
    // given
    fixture.slackSourceWithOneMessage("slack-a", "결제 장애 회고");

    // when
    List<ProjectionRun.Report> reports =
        fixture
            .projection(FixedEmbedder.zero(EMBEDDING_DIM), new SimpleMeterRegistry())
            .runDue(CancellationToken.none());

    // then: 투영이 끝까지 가서 쓴 신호다(다른 경로의 신호가 아니다)
    if (reports.size() != 1 || reports.getFirst().outcome() != ProjectionRun.Outcome.SUCCEEDED)
      throw new IllegalStateException("given 준비 실패: 투영 하나가 성공해야 한다 — " + reports);
    assertThat(fixture.lastProgressAt()).contains(MemoryProjection.T0);
  }
}
