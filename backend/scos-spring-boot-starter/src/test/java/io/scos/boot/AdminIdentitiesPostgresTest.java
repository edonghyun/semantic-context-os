package io.scos.boot;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.AdminIdentitiesContract;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.postgres.AdminStorageScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("PostgreSQL 신원 관리는 공통 정책을 지킨다(§7.1·U4)")
class AdminIdentitiesPostgresTest extends AdminIdentitiesContract {
  @Override
  protected Scenario newScenario() {
    PostgresSupport.truncateAll();
    return new IdentityScenario(new AdminStorageScenario(PostgresSupport.dataSource()));
  }

  private static final class IdentityScenario extends Scenario {
    private final AdminStorageScenario storage;
    private final AdminPort port;

    private IdentityScenario(AdminStorageScenario storage) {
      super(storage.store(), storage.identityReader());
      this.storage = storage;
      port =
          AdminConfiguration.newAdminPort(
              serviceStore(), CLOCK, POLICY, new FixedAdminDiagnostics());
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
