package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RuntimeConfigurationFenceContract;
import io.scos.collection.internal.RuntimeConfigurationFenceScenario;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 런타임 설정 fencing 계약")
class MemoryRuntimeConfigurationFenceTest extends RuntimeConfigurationFenceContract {
  @Override
  protected RuntimeConfigurationFenceScenario newContext() {
    var ports = CommitContext.Memory.create();
    var bodies = new MemoryBodyCompletionStore();
    return new RuntimeConfigurationFenceScenario(
        ports.ports(),
        bodies,
        adapter ->
            MemoryCollectionRuntime.over(
                    new MutableClock(BodyCommitScenario.NOW),
                    ports.sources(),
                    ports.claims(),
                    ports.runs())
                .rawItems(ports.rawItems())
                .bodies(bodies)
                .adapter(adapter)
                .build());
  }
}
