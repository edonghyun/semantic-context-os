package io.scos.engine.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.engine.admin.AdminBootstrap;
import io.scos.engine.admin.AdminBootstrap.BootstrapResult;
import io.scos.engine.admin.CommitFailureStore;
import io.scos.engine.admin.IssuedToken;
import io.scos.engine.admin.LockFailureStore;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.TokenHash;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("bootstrap 코어는 최초 관리자·토큰·감사·전달을 한 작업으로 묶는다")
class BootstrapAdminTest {
  @Test
  @DisplayName("최초 관리자와 토큰은 같은 상태에 저장되고 원문은 전달 경계로만 나간다")
  void bootstrapCreatesFirstAdminAndTokenAtomically() {
    // given
    var scenario = new Scenario();
    // when
    scenario.bootstrap();
    // then
    assertThat(scenario.users())
        .extracting(UserAccount::email, UserAccount::active, UserAccount::role)
        .containsExactly(tuple("admin@example.com", true, Role.ADMIN));
    assertThat(scenario.authenticates()).isTrue();
    assertThat(scenario.actions()).containsExactly("token.issue", "user.create");
  }

  @Test
  @DisplayName("완료 결과는 전달한 사용자·토큰 식별자만 포함한다")
  void bootstrapResultContainsOnlyIdentifiers() {
    // given
    var scenario = new Scenario();
    // when
    var result = scenario.bootstrap();
    // then
    assertThat(scenario.users()).extracting(UserAccount::id).containsExactly(result.userId());
    assertThat(result.tokenId()).isEqualTo(scenario.delivered.tokenId());
    assertThat(result.toString()).doesNotContain(scenario.delivered.value());
  }

  @Test
  @DisplayName("비활성 관리자도 최초 생성의 재실행을 막는다")
  void existingInactiveAdminPreventsBootstrap() {
    // given
    var scenario = new Scenario();
    scenario.givenInactiveAdministrator();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure)
        .isInstanceOf(AdminBootstrap.Failure.class)
        .hasMessage("already_initialized");
    assertThat(scenario.users()).hasSize(1);
    assertThat(scenario.actions()).isEmpty();
  }

  @Test
  @DisplayName("파일 전달 실패는 사용자·토큰·감사를 롤백한다")
  void fileFailureRollsBackBootstrap() {
    // given
    var scenario = new Scenario();
    scenario.givenDeliveryFailure();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure)
        .isInstanceOf(AdminBootstrap.Failure.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("injected file failure");
    assertThat(scenario.users()).isEmpty();
    assertThat(scenario.authenticates()).isFalse();
  }

  @Test
  @DisplayName("확정 롤백은 파일을 제거해도 되는 실패로 보고한다")
  void confirmedRollbackAllowsTokenFileRemoval() {
    // given
    var scenario = new Scenario();
    scenario.givenCommitRollback();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure)
        .isInstanceOf(AdminBootstrap.Failure.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("confirmed rollback");
    assertThat(((AdminBootstrap.Failure) failure).recoveryFileRequired()).isFalse();
    assertThat(scenario.users()).isEmpty();
  }

  @Test
  @DisplayName("커밋됐지만 응답을 잃었으면 복구 파일 보존이 필요하다")
  void uncertainCommitRetainsProtectedRecoveryFile() {
    // given
    var scenario = new Scenario();
    scenario.givenLostCommitResponse();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure)
        .isInstanceOf(AdminBootstrap.Failure.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("lost commit response");
    assertThat(((AdminBootstrap.Failure) failure).recoveryFileRequired()).isTrue();
    assertThat(scenario.authenticates()).isTrue();
  }

  @Test
  @DisplayName("재조회도 실패하면 확정 롤백으로 오인하지 않는다")
  void unavailableCommitOutcomeRequiresRecovery() {
    // given
    var scenario = new Scenario();
    scenario.givenUnqueryableCommitFailure();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure).isInstanceOf(AdminBootstrap.Failure.class);
    assertThat(((AdminBootstrap.Failure) failure).recoveryFileRequired()).isTrue();
  }

  @Test
  @DisplayName("최초 관리 잠금이 실패하면 사용자와 전달 파일을 만들지 않는다")
  void administrationLockFailurePreventsBootstrap() {
    var scenario = new Scenario();
    scenario.givenAdministrationLockFailure();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure)
        .isInstanceOf(AdminBootstrap.Failure.class)
        .hasRootCauseMessage("injected administration lock failure");
    assertThat(scenario.users()).isEmpty();
    assertThat(scenario.delivered).isNull();
  }

  @Test
  @DisplayName("롤백 재조회 잠금이 실패하면 파일을 보존해 복구에 사용한다")
  void recoveryLockFailureRequiresProtectedFile() {
    var scenario = new Scenario();
    scenario.givenRecoveryLockFailure();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure).isInstanceOf(AdminBootstrap.Failure.class);
    assertThat(((AdminBootstrap.Failure) failure).recoveryFileRequired()).isTrue();
    assertThat(scenario.users()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "admin", "@example.com", "admin@"})
  @DisplayName("잘못된 이메일은 저장과 전달 전에 거부한다")
  void invalidEmailDoesNotCreateAdministrator(String email) {
    // given
    var scenario = new Scenario();
    // when
    var failure = catchThrowable(() -> scenario.bootstrap(email));
    // then
    assertThat(failure).isInstanceOf(AdminBootstrap.Failure.class).hasMessage("invalid_request");
    assertThat(scenario.users()).isEmpty();
    assertThat(scenario.delivered).isNull();
  }

  @Test
  @DisplayName("파일 전달 실패는 감사도 남기지 않는다")
  void fileDeliveryFailureLeavesNoAudit() {
    // given
    var scenario = new Scenario();
    scenario.givenDeliveryFailure();
    // when
    var failure = catchThrowable(scenario::bootstrap);
    // then
    assertThat(failure).isInstanceOf(AdminBootstrap.Failure.class);
    assertThat(scenario.actions()).isEmpty();
    assertThat(((AdminBootstrap.Failure) failure).recoveryFileRequired()).isFalse();
  }

  private static final class Scenario {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
    private final MemoryAdminScenario storage = new MemoryAdminScenario();
    private final LockFailureStore locks = new LockFailureStore(storage.store());
    private AdminStore store = storage.store();
    private IssuedToken delivered;
    private boolean deliveryFailure;
    private boolean recoveryLockFailure;

    BootstrapResult bootstrap() {
      return bootstrap(" ADMIN@EXAMPLE.COM ");
    }

    BootstrapResult bootstrap(String email) {
      return new BootstrapAdmin(store, CLOCK)
          .bootstrap(
              email,
              token -> {
                delivered = token;
                if (recoveryLockFailure) locks.failNextAdministrationLock();
                if (deliveryFailure) throw new IllegalStateException("injected file failure");
              });
    }

    void givenDeliveryFailure() {
      deliveryFailure = true;
    }

    void givenAdministrationLockFailure() {
      store = locks;
      locks.failNextAdministrationLock();
    }

    void givenRecoveryLockFailure() {
      store = CommitFailureStore.rollbackNext(locks);
      recoveryLockFailure = true;
    }

    void givenInactiveAdministrator() {
      storage
          .store()
          .transact(
              session -> {
                session.insertUser(
                    new UserAccount(
                        "previous", "previous@example.com", false, Role.ADMIN, CLOCK.instant()));
                return null;
              });
    }

    void givenCommitRollback() {
      store = CommitFailureStore.rollbackNext(storage.store());
    }

    void givenLostCommitResponse() {
      store = CommitFailureStore.loseNextResponse(storage.store());
    }

    void givenUnqueryableCommitFailure() {
      store = CommitFailureStore.loseResponseAndConnection(storage.store());
    }

    List<UserAccount> users() {
      return storage.store().transact(session -> session.users(new PageRequest()).items());
    }

    boolean authenticates() {
      return delivered != null
          && storage.authentication().authenticate(TokenHash.of(delivered.value())).isPresent();
    }

    List<String> actions() {
      return storage
          .store()
          .transact(
              session ->
                  session.audit(new AuditQuery()).items().stream()
                      .map(event -> event.action())
                      .toList());
    }
  }
}
