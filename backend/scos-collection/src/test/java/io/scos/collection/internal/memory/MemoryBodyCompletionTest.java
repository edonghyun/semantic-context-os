package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.BodyCompletionStoreContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 본문 완료 저장소 계약")
class MemoryBodyCompletionTest extends BodyCompletionStoreContract {
  @Override
  protected BodyCompletionStore newStore() {
    return new MemoryBodyCompletionStore();
  }
}
