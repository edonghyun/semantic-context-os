package io.scos.collection.internal.memory;

import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.ReconciliationDiscoveryContract;
import io.scos.collection.internal.ReconciliationDiscoveryScenario;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 R2 발견 집계 계약")
class MemoryReconciliationDiscoveryTest extends ReconciliationDiscoveryContract {
  @Override
  protected ReconciliationDiscoveryScenario newContext() {
    return new ReconciliationDiscoveryScenario(
        CommitContext.Memory.create().ports(), new MemoryBodyCompletionStore());
  }
}
