package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStoreContract;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.json.JsonMapper;

@Tag("db")
@DisplayName("PostgreSQL 관리 저장소는 공통 계약을 지킨다")
class PostgresAdminStoreTest extends AdminStoreContract {
  private AdminStorageScenario scenario;

  @Override
  protected AdminStore newStore() {
    PostgresSupport.truncateAll();
    scenario = new AdminStorageScenario(PostgresSupport.dataSource());
    return scenario.store();
  }

  @Override
  protected void registerSource(SourceAccount source) {
    scenario.registerSource(source);
  }

  @Override
  protected UserTokens authentication() {
    return scenario.authentication();
  }

  @Override
  protected void seedAccessDirectory(AccessDirectory directory) {
    scenario.seed(directory);
  }

  @Override
  protected String serializeMetadata(Object metadata) {
    return JsonMapper.builder().build().writeValueAsString(metadata);
  }

  @Override
  protected IdentityStore identityReader() {
    return scenario.identityReader();
  }
}
