package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStoreContract;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("메모리 관리 저장소는 공통 계약을 지킨다")
class MemoryAdminStoreTest extends AdminStoreContract {
  private MemoryAdminScenario scenario;

  @Override
  protected AdminStore newStore() {
    scenario = new MemoryAdminScenario();
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

  @Test
  @DisplayName("인증이 다른 신원 상태를 보면 관리 저장소 조립을 거부한다")
  void tokenStoreMustShareIdentityState() {
    var wiring = new WiringScenario();
    // when·then
    assertThatThrownBy(wiring::withForeignTokens)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Administration must share identity state");
  }

  @Test
  @DisplayName("관측 원장이 다른 신원 상태를 보면 관리 저장소 조립을 거부한다")
  void sourceDirectoryMustShareIdentityState() {
    var wiring = new WiringScenario();
    // when·then
    assertThatThrownBy(wiring::withForeignDirectory)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Administration must share identity state");
  }

  @Test
  @DisplayName("실패한 트랜잭션은 처음 추가한 원천 관측도 복구한다")
  void rollbackRemovesNewDirectoryObservation() {
    var source = new SourceInstanceId("slack-rollback");
    scenario.registerSource(new SourceAccount(source, "slack"));
    var identity =
        new SourceIdentityRecord(
            "U01",
            Optional.of("reader@example.com"),
            true,
            false,
            false,
            Instant.parse("2026-11-08T00:00:00Z"));
    // when·then
    assertThatThrownBy(
            () ->
                scenario
                    .store()
                    .transact(
                        session -> {
                          scenario.observeIdentity(source, identity);
                          throw new IllegalStateException("injected directory rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected directory rollback");
    var observed = scenario.store().transact(session -> session.observedIdentities(source));
    assertThat(observed).isEmpty();
  }

  private static final class WiringScenario {
    private final MemoryIdentityStore identities = new MemoryIdentityStore();
    private final MemoryIdentityStore foreign = new MemoryIdentityStore();

    MemoryAdminStore withForeignTokens() {
      return new MemoryAdminStore(
          identities, new MemoryUserTokens(foreign), new MemorySourceAccessDirectory(identities));
    }

    MemoryAdminStore withForeignDirectory() {
      return new MemoryAdminStore(
          identities, new MemoryUserTokens(identities), new MemorySourceAccessDirectory(foreign));
    }
  }
}
