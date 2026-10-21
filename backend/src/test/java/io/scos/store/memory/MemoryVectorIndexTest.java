package io.scos.store.memory;

import io.scos.spi.store.VectorIndex;
import io.scos.spi.store.VectorIndexContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("벡터 색인 계약 — 메모리 포트(CONTRACTS B4)")
class MemoryVectorIndexTest extends VectorIndexContract {
  @Override
  protected VectorIndex newIndex() {
    return new MemoryVectorIndex();
  }
}
