package io.scos.boot;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminUsersContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.store.postgres.AdminStorageScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("PostgreSQL 사용자 관리는 공통 정책을 지킨다(§7.1)")
class AdminUsersPostgresTest extends AdminUsersContract {
  @Override
  protected Scenario newScenario() {
    PostgresSupport.truncateAll();
    return new PostgresScenario(new AdminStorageScenario(PostgresSupport.dataSource()));
  }

  private static final class PostgresScenario extends Scenario {
    private final AdminPort port;

    private PostgresScenario(AdminStorageScenario storage) {
      super(storage.store(), storage.authentication());
      port =
          AdminConfiguration.newAdminPort(
              serviceStore(), CLOCK, POLICY, new FixedAdminDiagnostics());
    }

    @Override
    public AdminPort port() {
      return port;
    }
  }
}
