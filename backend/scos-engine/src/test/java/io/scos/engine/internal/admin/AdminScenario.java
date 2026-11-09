package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminUsersContract.Scenario;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.store.memory.MemoryAdminScenario;

final class AdminScenario extends Scenario {
  private final AdminPort port;

  AdminScenario() {
    this(new MemoryAdminScenario());
  }

  private AdminScenario(MemoryAdminScenario storage) {
    super(storage.store(), storage.authentication());
    port = new AdminService(serviceStore(), CLOCK, new FixedAdminDiagnostics());
  }

  @Override
  public AdminPort port() {
    return port;
  }
}
