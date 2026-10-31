package io.scos.collection.internal.memory;

import io.scos.collection.internal.AbsenceVerificationContract;
import io.scos.collection.internal.AbsenceVerificationScenario;
import io.scos.collection.internal.CommitContext;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 전체 부재 검증 계약")
class MemoryAbsenceVerificationTest extends AbsenceVerificationContract {
  @Override
  protected AbsenceVerificationScenario newContext() {
    return new AbsenceVerificationScenario(
        CommitContext.Memory.create().ports(), new MemoryBodyCompletionStore());
  }
}
