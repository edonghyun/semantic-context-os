package io.scos.store.memory;

import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.EmbeddingCacheContract;
import org.junit.jupiter.api.DisplayName;

/** 임베딩 캐시 계약 — 메모리. */
@DisplayName("임베딩 캐시 계약 — 메모리 포트(ADR-0026)")
class MemoryEmbeddingCacheTest extends EmbeddingCacheContract {
  @Override
  protected EmbeddingCache newCache() {
    return new MemoryEmbeddingCache();
  }
}
