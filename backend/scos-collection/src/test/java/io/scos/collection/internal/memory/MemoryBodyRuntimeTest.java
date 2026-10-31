package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.BodyRuntimeScenario;
import io.scos.collection.internal.BodyRuntimeStateContract;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 런타임의 본문 요약 계약")
class MemoryBodyRuntimeTest extends BodyRuntimeStateContract {
  @Override
  protected BodyRuntimeScenario newScenario() {
    var ports = CommitContext.Memory.create();
    var bodies = new MemoryBodyCompletionStore();
    return new BodyRuntimeScenario(
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
