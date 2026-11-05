package io.scos.collection.internal;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 PageAclCommitTest 공통 계약")
class PageAclCommitTest extends PageAclCommitContract {
  @Override
  protected AclCommitScenario newScenario() {
    return AclCommitScenario.memory();
  }
}
