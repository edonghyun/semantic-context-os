package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStoreContract;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("잠금 장애 주입도 공통 관리 저장 계약과 실제 롤백을 지킨다(T-c16)")
class LockFailureStoreTest extends AdminStoreContract {
  private MemoryAdminScenario scenario;
  private LockFailureStore faults;

  @Override
  protected AdminStore newStore() {
    scenario = new MemoryAdminScenario();
    faults = new LockFailureStore(scenario.store());
    return faults;
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

  @Test
  @DisplayName("잠금 획득 장애가 나면 앞선 쓰기도 실제 저장소에서 롤백된다")
  void lockFailureRollsBackRealWrites() {
    faults.failNextAdministrationLock();
    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.insertUser(
                          new UserAccount(
                              "pending",
                              "pending@example.com",
                              true,
                              Role.MEMBER,
                              Instant.parse("2026-11-08T00:00:00Z")));
                      session.lockAdministration();
                      return true;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected administration lock failure");
    var users = store.transact(session -> session.users(new PageRequest()).items());
    assertThat(users).isEmpty();
  }
}
