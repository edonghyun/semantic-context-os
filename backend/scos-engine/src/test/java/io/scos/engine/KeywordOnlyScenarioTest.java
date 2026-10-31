package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.embedding.FixedEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("벡터 쓰기 관찰은 실제 색인 저장을 반영한다(TESTING §4.2/C7)")
class KeywordOnlyScenarioTest {
  final KeywordOnlyScenario scenario = new KeywordOnlyScenario();

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  @Nested
  @DisplayName("실제 임베더를 쓰는 투영기가 준비된 상태에서")
  class WithEmbedderIngestor {
    ContextIngestor ingestor;

    @BeforeEach
    void givenEmbedderIngestor() {
      ingestor = scenario.ingestorBuilder().embedder(FixedEmbedder.zero(2)).build();
    }

    @Test
    @DisplayName("실제 임베더 조립의 벡터 쓰기를 한 번 기록한다")
    void recordsVectorWrite() {
      // when
      scenario.ingestWith(ingestor);

      // then
      assertThat(scenario.vectorUpserts()).isEqualTo(1);
    }

    @Test
    @DisplayName("관찰을 거친 벡터 쓰기는 실제 메모리 색인에 저장된다")
    void forwardsVectorWrite() {
      // when
      scenario.ingestWith(ingestor);

      // then
      assertThat(scenario.storedVectors().values()).containsExactly(new double[] {0, 0});
    }
  }
}
