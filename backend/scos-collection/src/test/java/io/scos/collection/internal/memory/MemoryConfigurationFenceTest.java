package io.scos.collection.internal.memory;

import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.ConfigurationFenceContract;
import io.scos.collection.internal.ConfigurationFenceScenario;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 설정 fencing 계약")
class MemoryConfigurationFenceTest extends ConfigurationFenceContract {
  @Override
  protected ConfigurationFenceScenario newContext() {
    return new ConfigurationFenceScenario(
        CommitContext.Memory.create().ports(), new MemoryBodyCompletionStore());
  }
}
