package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminTokensContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.store.memory.MemoryAdminScenario;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 토큰 관리는 공통 정책을 지킨다(§6.1·§7.1)")
class AdminTokensTest extends AdminTokensContract {
  @Override
  protected Scenario newScenario() {
    return new TokenScenario(new MemoryAdminScenario());
  }

  private static final class TokenScenario extends Scenario {
    private final AdminPort port;

    private TokenScenario(MemoryAdminScenario storage) {
      super(storage.store(), storage.authentication());
      port = new AdminService(tokenStore(), tokenClock(), new FixedAdminDiagnostics());
    }

    @Override
    public AdminPort port() {
      return port;
    }
  }
}
