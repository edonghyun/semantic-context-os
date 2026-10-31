package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.CommitContext;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RuntimeAbsenceVerificationContract;
import io.scos.collection.internal.RuntimeAbsenceVerificationScenario;
import io.scos.engine.support.time.MutableClock;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 런타임 부재 검증 계약")
class MemoryRuntimeAbsenceVerificationTest extends RuntimeAbsenceVerificationContract {
  @Override
  protected RuntimeAbsenceVerificationScenario newContext() {
    var ports = CommitContext.Memory.create();
    return new RuntimeAbsenceVerificationScenario(
        ports.ports(),
        adapter ->
            MemoryCollectionRuntime.over(
                    new MutableClock(BodyCommitScenario.NOW),
                    ports.sources(),
                    ports.claims(),
                    ports.runs())
                .rawItems(ports.rawItems())
                .adapter(adapter)
                .build());
  }
}
