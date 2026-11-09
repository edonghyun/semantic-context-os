package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminIdentitiesContract;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminScenario;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 신원 관리는 공통 정책을 지킨다(§7.1·U4)")
class AdminIdentitiesTest extends AdminIdentitiesContract {
  @Override
  protected Scenario newScenario() {
    return new IdentityScenario(new MemoryAdminScenario());
  }

  private static final class IdentityScenario extends Scenario {
    private final MemoryAdminScenario storage;
    private final AdminPort port;

    private IdentityScenario(MemoryAdminScenario storage) {
      super(storage.store(), storage.identityReader());
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

    @Override
    protected void observe(SourceInstanceId source, SourceIdentityRecord identity) {
      storage.observeIdentity(source, identity);
    }
  }
}
