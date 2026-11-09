package io.scos.collection.internal;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 수집 등록은 관리 원장을 갱신한다(T-c2)")
class SourceRegistrationAdminTest extends SourceRegistrationAdminContract {
  @Override
  protected SourceRegistrationAdminScenario newScenario() {
    return SourceRegistrationAdminScenario.memory();
  }
}
