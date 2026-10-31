package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCompletionContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 본문 완료 유스케이스 계약")
class MemoryBodyScopeTest extends BodyCompletionContract {
  @Override
  protected void prepareStores() {
    bodies = new MemoryBodyCompletionStore();
    raw = new MemoryRawItemStore();
  }
}
