package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminScopesContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryAdminScenario;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 조직 전체 지정은 공통 정책을 지킨다(§7.1)")
class AdminScopesTest extends AdminScopesContract {
  @Override
  protected Scenario newScenario() {
    return new ScopeScenario(new MemoryAdminScenario());
  }

  private static final class ScopeScenario extends Scenario {
    private final MemoryAdminScenario storage;
    private final AdminPort port;

    private ScopeScenario(MemoryAdminScenario storage) {
      super(storage.store(), storage.identityReader(), new MemoryAclStore());
      this.storage = storage;
      port = new AdminService(serviceStore(), CLOCK, new FixedAdminDiagnostics());
    }

    @Override
    public AdminPort port() {
      return port;
    }

    @Override
    public void registerSource(SourceAccount source) {
      storage.registerSource(source);
    }
  }
}
