package io.scos.engine.internal.projection;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderContract;
import org.junit.jupiter.api.DisplayName;

/** 인제스트 테스트의 기록 임베더가 실제 어댑터와 같은 임베더 계약을 지킨다. */
@DisplayName("기록 임베더는 임베더 계약을 지킨다")
class RecordingEmbedderTest extends EmbedderContract {
  @Override
  protected Embedder newEmbedder() {
    return new RecordingEmbedder();
  }
}
