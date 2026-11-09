package io.scos.boot;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminTokensContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.store.postgres.AdminStorageScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("PostgreSQL 토큰 관리는 공통 정책을 지킨다(§6.1·§7.1)")
class AdminTokensPostgresTest extends AdminTokensContract {
  @Override
  protected Scenario newScenario() {
    PostgresSupport.truncateAll();
    return new TokenScenario(new AdminStorageScenario(PostgresSupport.dataSource()));
  }

  private static final class TokenScenario extends Scenario {
    private final AdminPort port;

    private TokenScenario(AdminStorageScenario storage) {
      super(storage.store(), storage.authentication());
      port =
          AdminConfiguration.newAdminPort(
              tokenStore(), tokenClock(), POLICY, new FixedAdminDiagnostics());
    }

    @Override
    public AdminPort port() {
      return port;
    }
  }
}
