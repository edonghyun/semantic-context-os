package io.scos.engine;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 엔진은 요청자가 볼 수 있는 문서만 내보낸다")
class ContextEngineAccessTest extends ContextEngineAccessContract {
  @Override
  protected AccessScenario scenario() {
    return new AccessScenario();
  }
}
