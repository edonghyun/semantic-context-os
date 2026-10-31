package io.scos.spi.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

/** 고정 응답 임베더가 실제 어댑터와 같은 임베더 계약을 지킨다. */
@DisplayName("고정 응답 임베더는 임베더 계약을 지킨다")
class FixedEmbedderTest {
  static final int DIM = 3;

  @Nested
  @DisplayName("영벡터 판")
  class Zero extends EmbedderContract {
    @Override
    protected Embedder newEmbedder() {
      return FixedEmbedder.zero(DIM);
    }
  }

  @Nested
  @DisplayName("키워드 판")
  class ByKeyword extends EmbedderContract {
    @Override
    protected Embedder newEmbedder() {
      return FixedEmbedder.byKeyword("정산", new double[] {1, 0, 0}, new double[] {0, 1, 0});
    }
  }
}
