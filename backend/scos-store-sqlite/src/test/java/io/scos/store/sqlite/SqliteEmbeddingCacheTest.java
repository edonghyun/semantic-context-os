package io.scos.store.sqlite;

import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.EmbeddingCacheContract;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("임베딩 캐시 계약 — SQLite 포트(ADR-0026)")
class SqliteEmbeddingCacheTest extends EmbeddingCacheContract {
  @TempDir Path dir;

  @Override
  protected EmbeddingCache newCache() {
    return SqliteIndex.open(dir.resolve("index.sqlite")).embeddingCache();
  }
}
