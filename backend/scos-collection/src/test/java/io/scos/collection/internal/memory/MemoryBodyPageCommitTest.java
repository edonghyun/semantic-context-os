package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.BodyPageCommitContract;
import io.scos.collection.internal.CommitContext;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 본문 페이지 커밋 계약")
class MemoryBodyPageCommitTest extends BodyPageCommitContract {
  @Override
  protected BodyCommitScenario newContext() {
    return new BodyCommitScenario(
        CommitContext.Memory.create().ports(), new MemoryBodyCompletionStore());
  }
}
