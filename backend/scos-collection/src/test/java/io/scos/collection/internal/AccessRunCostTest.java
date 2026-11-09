package io.scos.collection.internal;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 접근 비용은 run별 저장과 조회 계약을 지킨다(T-c10a/b)")
class AccessRunCostTest extends AccessRunCostContract {
  @Override
  protected AccessRunCostQueryScenario newQueryScenario() {
    return AccessRunCostQueryScenario.memory();
  }

  @Override
  protected AccessRunScenario newScenario() {
    return AccessRunScenario.memory();
  }
}
