package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("커밋 장애 주입은 실제 저장소의 롤백·응답 유실·재조회 상태를 구별한다")
class CommitFailureStoreTest {
  @Test
  @DisplayName("커밋 롤백을 주입하면 변경은 저장되지 않는다")
  void rollbackDoesNotPersistWrites() {
    // given
    var scenario = new Scenario();
    scenario.givenCommitRollback();
    // when
    var failure = catchThrowable(scenario::createUser);
    // then
    assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("confirmed rollback");
    assertThat(scenario.persistedUserCount()).isZero();
  }

  @Test
  @DisplayName("커밋 응답만 잃으면 저장된 변경을 재조회할 수 있다")
  void lostResponsePersistsQueryableWrites() {
    // given
    var scenario = new Scenario();
    scenario.givenLostCommitResponse();
    // when
    var failure = catchThrowable(scenario::createUser);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("lost commit response");
    assertThat(scenario.userCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("응답과 연결을 잃으면 커밋 뒤 재조회도 실패한다")
  void lostConnectionPreventsOutcomeQuery() {
    // given
    var scenario = new Scenario();
    scenario.givenCommittedButDisconnected();
    // when
    var failure = catchThrowable(scenario::userCount);
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");
    assertThat(scenario.persistedUserCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("확정 롤백 다음 트랜잭션은 정상적으로 커밋한다")
  void transactionAfterRollbackCanCommit() {
    // given
    var scenario = new Scenario();
    scenario.givenPreviouslyRolledBackWork();
    // when
    scenario.createUser();
    // then
    assertThat(scenario.userCount()).isEqualTo(1);
  }

  private static final class Scenario {
    private final MemoryAdminScenario storage = new MemoryAdminScenario();
    private AdminStore store = storage.store();

    void givenCommitRollback() {
      store = CommitFailureStore.rollbackNext(storage.store());
    }

    void givenLostCommitResponse() {
      store = CommitFailureStore.loseNextResponse(storage.store());
    }

    void givenCommittedButDisconnected() {
      store = CommitFailureStore.loseResponseAndConnection(storage.store());
      expectFailure("lost commit response");
    }

    void givenPreviouslyRolledBackWork() {
      givenCommitRollback();
      expectFailure("confirmed rollback");
    }

    private void expectFailure(String message) {
      var failure = catchThrowable(this::createUser);
      if (!(failure instanceof IllegalStateException) || !message.equals(failure.getMessage()))
        throw new IllegalStateException("given 준비 실패: 커밋 장애가 주입되지 않음", failure);
    }

    void createUser() {
      store.transact(
          session -> {
            session.insertUser(
                new UserAccount(
                    "synthetic-user",
                    "admin@example.com",
                    true,
                    Role.ADMIN,
                    Instant.parse("2026-11-08T00:00:00Z")));
            return null;
          });
    }

    int userCount() {
      return store.transact(session -> session.users(new PageRequest()).items().size());
    }

    int persistedUserCount() {
      return storage.store().transact(session -> session.users(new PageRequest()).items().size());
    }
  }
}
