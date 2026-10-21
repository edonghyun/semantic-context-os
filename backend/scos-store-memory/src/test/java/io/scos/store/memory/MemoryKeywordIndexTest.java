package io.scos.store.memory;

import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.KeywordIndexContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("키워드 색인 계약 — 메모리 포트(CONTRACTS B3)")
class MemoryKeywordIndexTest extends KeywordIndexContract {
  @Override
  protected KeywordIndex newIndex() {
    return new MemoryKeywordIndex();
  }
}
