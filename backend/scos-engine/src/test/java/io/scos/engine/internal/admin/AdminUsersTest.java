package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminUsersContract;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 사용자 관리는 공통 정책을 지킨다(§7.1)")
class AdminUsersTest extends AdminUsersContract {
  @Override
  protected Scenario newScenario() {
    return new AdminScenario();
  }
}
