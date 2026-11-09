package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 메모리와 PostgreSQL에서 실제 관리 포트의 같은 사용자 정책을 실행한다. */
@DisplayName("사용자 관리는 현재 활성 admin과 원자적 감사를 요구한다(§7.1)")
public abstract class AdminUsersContract {
  protected static final Principal ADMIN = new Principal("admin");
  protected static final Principal MEMBER = new Principal("member");
  protected static final Principal SECOND = new Principal("second-admin");
  protected Scenario scenario;

  protected abstract Scenario newScenario();

  @BeforeEach
  void setUpAdministration() {
    scenario = newScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("member", "member@example.com", true, Role.MEMBER);
  }

  @Nested
  @DisplayName("구성원 Principal로 직접 요청하는 상태에서")
  class MemberRequests {
    @Test
    @DisplayName("사용자 생성은 forbidden이고, 목록은 그대로다")
    void memberCannotCreateUser() {
      // when
      var failure =
          catchThrowable(
              () -> scenario.port().createUser(MEMBER, "alice@example.com", Role.MEMBER));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.users())
          .extracting(UserAccount::email)
          .containsExactly("admin@example.com", "member@example.com");
    }

    @Test
    @DisplayName("비활성화는 forbidden이고, 대상은 활성 상태다")
    void memberCannotDeactivateUser() {
      // when
      var failure = catchThrowable(() -> scenario.port().deactivateUser(MEMBER, "admin"));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.user("admin").active()).isTrue();
    }

    @Test
    @DisplayName("활성화는 forbidden이고, 대상은 비활성 상태다")
    void memberCannotActivateUser() {
      // given
      scenario.givenUser("inactive-member", "inactive-member@example.com", false, Role.MEMBER);
      // when
      var failure = catchThrowable(() -> scenario.port().activateUser(MEMBER, "inactive-member"));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.user("inactive-member").active()).isFalse();
    }

    @Test
    @DisplayName("역할 변경은 forbidden이고, 역할은 그대로다")
    void memberCannotChangeRole() {
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(MEMBER, "member", Role.ADMIN));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.user("member").role()).isEqualTo(Role.MEMBER);
    }

    @Test
    @DisplayName("사용자 목록 조회는 forbidden이다")
    void memberCannotListUsers() {
      // when
      var failure = catchThrowable(() -> scenario.port().users(MEMBER, new PageRequest()));
      // then
      assertFailure(failure, "forbidden");
    }
  }

  @Nested
  @DisplayName("활성 admin이 관리하는 상태에서")
  class AdministratorRequests {
    @Test
    @DisplayName("사용자를 생성하면 이메일을 정규화해 저장한다")
    void createNormalizesEmail() {
      // when
      var created = scenario.port().createUser(ADMIN, " Alice@Example.COM ", Role.MEMBER);
      // then
      assertThat(scenario.user(created.id()).email()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("새 사용자는 서버 UUID와 현재 생성 시각을 가진다")
    void createdUserHasServerIdentityAndTimestamp() {
      // when
      var created = scenario.port().createUser(ADMIN, "alice@example.com", Role.ADMIN);
      // then
      assertThat(created.id()).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
      assertThat(created)
          .extracting(UserAccount::active, UserAccount::role, UserAccount::createdAt)
          .containsExactly(true, Role.ADMIN, Instant.parse("2026-11-08T00:00:00Z"));
    }

    @Test
    @DisplayName("중복 이메일은 conflict이고, 생성 감사는 늘지 않는다")
    void duplicateNormalizedEmailIsConflict() {
      // when
      var failure =
          catchThrowable(
              () -> scenario.port().createUser(ADMIN, " MEMBER@Example.COM ", Role.ADMIN));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.audit("user.create")).isEmpty();
    }

    @Test
    @DisplayName("사용자 생성 감사는 요청한 admin을 기록한다")
    void createUserRecordsActor() {
      // when
      var created = scenario.port().createUser(ADMIN, "alice@example.com", Role.MEMBER);
      // then
      assertThat(scenario.audit("user.create"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::at)
          .containsExactly(
              "admin", "user.create", "user", created.id(), Instant.parse("2026-11-08T00:00:00Z"));
    }

    @Test
    @DisplayName("비활성화하면 기존 토큰 모두 인증되지 않는다")
    void deactivationRevokesEveryToken() {
      // given
      scenario.givenTwoMemberTokens();
      // when
      scenario.port().deactivateUser(ADMIN, "member");
      // then
      assertThat(scenario.authenticates("member-work")).isEmpty();
      assertThat(scenario.authenticates("member-home")).isEmpty();
    }

    @Test
    @DisplayName("재활성화해도 폐기된 토큰은 복구되지 않는다")
    void reactivationDoesNotRestoreRevokedTokens() {
      // given
      scenario.givenDeactivatedMemberWithTokens();
      // when
      scenario.port().activateUser(ADMIN, "member");
      // then
      assertThat(scenario.authenticates("member-work")).isEmpty();
      assertThat(scenario.authenticates("member-home")).isEmpty();
    }

    @Test
    @DisplayName("활성화하면 사용자가 활성 상태로 조회된다")
    void activationMakesUserActive() {
      // given
      scenario.givenActive("member", false);
      // when
      scenario.port().activateUser(ADMIN, "member");
      // then
      assertThat(scenario.user("member").active()).isTrue();
    }

    @Test
    @DisplayName("역할 변경 감사는 이전 역할과 새 역할을 기록한다")
    void roleChangeIsAudited() {
      // when
      scenario.port().changeRole(ADMIN, "member", Role.ADMIN);
      // then
      assertThat(scenario.audit("user.role"))
          .singleElement()
          .extracting(
              AuditEvent::actor, AuditEvent::targetType, AuditEvent::targetId, AuditEvent::detail)
          .containsExactly("admin", "user", "member", Map.of("old", "member", "new", "admin"));
    }

    @Test
    @DisplayName("역할 변경은 현재 사용자 조회에 반영된다")
    void roleChangeIsStored() {
      // when
      scenario.port().changeRole(ADMIN, "member", Role.ADMIN);
      // then
      assertThat(scenario.user("member").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("비활성화는 요청 주체와 대상을 감사에 기록한다")
    void deactivationIsAudited() {
      // when
      scenario.port().deactivateUser(ADMIN, "member");
      // then
      assertThat(scenario.audit("user.deactivate"))
          .singleElement()
          .extracting(AuditEvent::actor, AuditEvent::targetType, AuditEvent::targetId)
          .containsExactly("admin", "user", "member");
    }

    @Test
    @DisplayName("활성화는 요청 주체와 대상을 감사에 기록한다")
    void activationIsAudited() {
      // given
      scenario.givenActive("member", false);
      // when
      scenario.port().activateUser(ADMIN, "member");
      // then
      assertThat(scenario.audit("user.activate"))
          .singleElement()
          .extracting(AuditEvent::actor, AuditEvent::targetType, AuditEvent::targetId)
          .containsExactly("admin", "user", "member");
    }

    @Test
    @DisplayName("다른 활성 admin이 유효 토큰을 가지면 본인을 비활성화할 수 있다")
    void secondAdminAllowsSelfDeactivation() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "second-admin", "work");
      // when
      scenario.port().deactivateUser(ADMIN, "admin");
      // then
      assertThat(scenario.user(SECOND, "admin").active()).isFalse();
    }

    @Test
    @DisplayName("다른 활성 admin이 유효 토큰을 가지면 본인을 강등할 수 있다")
    void secondAdminAllowsSelfDemotion() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "second-admin", "work");
      // when
      scenario.port().changeRole(ADMIN, "admin", Role.MEMBER);
      // then
      assertThat(scenario.user(SECOND, "admin").role()).isEqualTo(Role.MEMBER);
    }

    @Test
    @DisplayName("토큰 없는 활성 admin만 남기는 본인 비활성화는 conflict이고 사용자 상태를 보존한다")
    void selfDeactivationConflictsWhenRemainingAdministratorHoldsNoToken() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      var failure = catchThrowable(() -> scenario.port().deactivateUser(ADMIN, "admin"));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.user(SECOND, "admin").active()).isTrue();
    }

    @Test
    @DisplayName("거부한 본인 비활성화는 기존 관리자 토큰의 인증을 보존한다")
    void rejectedSelfDeactivationPreservesAdministratorToken() {
      // given
      scenario.givenSecondAdmin();
      var token = scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      assertThatThrownBy(() -> scenario.port().deactivateUser(ADMIN, "admin"))
          .isInstanceOf(AdminPort.Failure.class);
      // then
      assertThat(scenario.authenticates(token.value())).contains(new Principal("admin"));
    }

    @Test
    @DisplayName("거부한 본인 비활성화는 감사를 추가하지 않는다")
    void rejectedSelfDeactivationDoesNotAudit() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      assertThatThrownBy(() -> scenario.port().deactivateUser(ADMIN, "admin"))
          .isInstanceOf(AdminPort.Failure.class);
      // then
      assertThat(scenario.audit("user.deactivate")).isEmpty();
    }

    @Test
    @DisplayName("토큰 없는 활성 admin만 남기는 본인 강등은 conflict이고 사용자 상태를 보존한다")
    void selfDemotionConflictsWhenRemainingAdministratorHoldsNoToken() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(ADMIN, "admin", Role.MEMBER));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.user(SECOND, "admin").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("거부한 본인 강등은 기존 관리자 토큰의 인증을 보존한다")
    void rejectedSelfDemotionPreservesAdministratorToken() {
      // given
      scenario.givenSecondAdmin();
      var token = scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      assertThatThrownBy(() -> scenario.port().changeRole(ADMIN, "admin", Role.MEMBER))
          .isInstanceOf(AdminPort.Failure.class);
      // then
      assertThat(scenario.authenticates(token.value())).contains(new Principal("admin"));
    }

    @Test
    @DisplayName("거부한 본인 강등은 감사를 추가하지 않는다")
    void rejectedSelfDemotionDoesNotAudit() {
      // given
      scenario.givenSecondAdmin();
      scenario.port().issueToken(ADMIN, "admin", "work");
      // when
      assertThatThrownBy(() -> scenario.port().changeRole(ADMIN, "admin", Role.MEMBER))
          .isInstanceOf(AdminPort.Failure.class);
      // then
      assertThat(scenario.audit("user.role")).isEmpty();
    }

    @Test
    @DisplayName("마지막 활성 admin의 비활성화는 conflict다")
    void lastActiveAdminCannotBeDeactivated() {
      // when
      var failure = catchThrowable(() -> scenario.port().deactivateUser(ADMIN, "admin"));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.user("admin").active()).isTrue();
    }

    @Test
    @DisplayName("마지막 활성 admin의 강등은 conflict다")
    void lastActiveAdminCannotBeDemoted() {
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(ADMIN, "admin", Role.MEMBER));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.user("admin").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("비활성 admin만 더 있으면 마지막 admin 강등은 conflict다")
    void inactiveAdminDoesNotAllowLastAdminDemotion() {
      // given
      scenario.givenUser("second-admin", "second-admin@example.com", false, Role.ADMIN);
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(ADMIN, "admin", Role.MEMBER));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.user("admin").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("없는 사용자를 비활성화하면 not_found다")
    void unknownDeactivationTargetIsNotFound() {
      // when
      var failure = catchThrowable(() -> scenario.port().deactivateUser(ADMIN, "missing"));
      // then
      assertFailure(failure, "not_found");
    }

    @Test
    @DisplayName("없는 사용자를 활성화하면 not_found다")
    void unknownActivationTargetIsNotFound() {
      // when
      var failure = catchThrowable(() -> scenario.port().activateUser(ADMIN, "missing"));
      // then
      assertFailure(failure, "not_found");
    }

    @Test
    @DisplayName("없는 사용자의 역할을 바꾸면 not_found다")
    void unknownRoleTargetIsNotFound() {
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(ADMIN, "missing", Role.ADMIN));
      // then
      assertFailure(failure, "not_found");
    }

    @Test
    @DisplayName("잘못된 목록 커서는 invalid_request다")
    void invalidUserCursorIsInvalidRequest() {
      // when
      var failure =
          catchThrowable(
              () -> scenario.port().users(ADMIN, new PageRequest(50, Optional.of("invalid"))));
      // then
      assertFailure(failure, "invalid_request");
    }

    @Test
    @DisplayName("비활성 상태를 다시 요청하면 감사가 늘지 않는다")
    void repeatedDeactivationDoesNotAudit() {
      // given
      scenario.givenActive("member", false);
      // when
      scenario.port().deactivateUser(ADMIN, "member");
      // then
      assertThat(scenario.audit("user.deactivate")).isEmpty();
    }

    @Test
    @DisplayName("활성 상태를 다시 요청하면 감사가 늘지 않는다")
    void repeatedActivationDoesNotAudit() {
      // when
      scenario.port().activateUser(ADMIN, "member");
      // then
      assertThat(scenario.audit("user.activate")).isEmpty();
    }

    @Test
    @DisplayName("같은 역할을 요청하면 감사가 늘지 않는다")
    void repeatedRoleChangeDoesNotAudit() {
      // when
      scenario.port().changeRole(ADMIN, "member", Role.MEMBER);
      // then
      assertThat(scenario.audit("user.role")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "alice", "@example.com", "alice@"})
    @DisplayName("이메일 필수 부분이 비어 있으면 invalid_request다")
    void malformedEmailIsInvalidRequest(String email) {
      // when
      var failure = catchThrowable(() -> scenario.port().createUser(ADMIN, email, Role.MEMBER));
      // then
      assertFailure(failure, "invalid_request");
      assertThat(scenario.audit("user.create")).isEmpty();
    }
  }

  @Nested
  @DisplayName("요청한 admin의 현재 권한이 사라진 상태에서")
  class RemovedAuthority {
    @Test
    @DisplayName("비활성 admin의 변경 요청은 forbidden이다")
    void inactiveAdminCannotMutateUsers() {
      // given
      scenario.givenActive("admin", false);
      // when
      var failure =
          catchThrowable(() -> scenario.port().createUser(ADMIN, "alice@example.com", Role.MEMBER));
      // then
      assertFailure(failure, "forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "local"})
    @DisplayName("등록되지 않은 Principal의 관리 요청은 forbidden이다")
    void unknownPrincipalCannotAdminister(String userId) {
      // when
      var failure =
          catchThrowable(
              () ->
                  scenario
                      .port()
                      .createUser(new Principal(userId), "alice@example.com", Role.MEMBER));
      // then
      assertFailure(failure, "forbidden");
    }

    @Test
    @DisplayName("비활성 admin의 목록 조회는 forbidden이다")
    void inactiveAdminCannotListUsers() {
      // given
      scenario.givenActive("admin", false);
      // when
      var failure = catchThrowable(() -> scenario.port().users(ADMIN, new PageRequest()));
      // then
      assertFailure(failure, "forbidden");
    }

    @Test
    @DisplayName("강등이 먼저 커밋되면 대기 중인 변경 요청은 forbidden이다")
    void demotionCommittedBeforeMutationDeniesAdmin() throws Exception {
      // given
      scenario.givenSecondAdmin();
      try (var race = scenario.holdDemotion()) {
        // when
        race.requestCreation();
        // then
        assertThatThrownBy(race::awaitWhileDemotionPending).isInstanceOf(TimeoutException.class);
        race.commitDemotion();
        assertFailure(race.mutationFailure(), "forbidden");
        assertThat(scenario.users(SECOND))
            .extracting(UserAccount::email)
            .doesNotContain("alice@example.com");
      }
    }
  }

  @Nested
  @DisplayName("변경 action의 감사 저장이 실패하는 상태에서")
  class FailedAudit {
    @Test
    @DisplayName("감사 저장 실패 메시지는 SQL과 이메일을 노출하지 않는다")
    void auditFailureDoesNotExposeStorageDetails() {
      // given
      scenario.failAudit("user.create");
      // when
      var failure =
          catchThrowable(() -> scenario.port().createUser(ADMIN, "alice@example.com", Role.MEMBER));
      // then
      assertThat(failure).isInstanceOf(IllegalStateException.class);
      assertThat(failure.getMessage())
          .doesNotContain("alice@example.com", "INSERT", "audit_events", "Audit actor", "not-null");
    }

    @Test
    @DisplayName("생성 감사 실패는 새 사용자를 롤백한다")
    void createUserRollsBackWhenAuditFails() {
      // given
      scenario.failAudit("user.create");
      // when
      var failure =
          catchThrowable(() -> scenario.port().createUser(ADMIN, "alice@example.com", Role.MEMBER));
      // then
      assertThat(failure).isInstanceOf(RuntimeException.class);
      assertThat(scenario.users())
          .extracting(UserAccount::email)
          .doesNotContain("alice@example.com");
      assertThat(scenario.audit("user.create")).isEmpty();
    }

    @Test
    @DisplayName("비활성화 감사 실패는 활성 상태와 토큰을 보존한다")
    void deactivationRollsBackWhenAuditFails() {
      // given
      scenario.givenTwoMemberTokens();
      scenario.failAudit("user.deactivate");
      // when
      var failure = catchThrowable(() -> scenario.port().deactivateUser(ADMIN, "member"));
      // then
      assertThat(failure).isInstanceOf(RuntimeException.class);
      assertThat(
              List.of(
                  scenario.user("member").active(),
                  scenario.authenticates("member-work"),
                  scenario.authenticates("member-home"),
                  scenario.audit("user.deactivate")))
          .containsExactly(
              true,
              Optional.of(new Principal("member")),
              Optional.of(new Principal("member")),
              List.of());
    }

    @Test
    @DisplayName("활성화 감사 실패는 비활성 상태를 보존한다")
    void activationRollsBackWhenAuditFails() {
      // given
      scenario.givenActive("member", false);
      scenario.failAudit("user.activate");
      // when
      var failure = catchThrowable(() -> scenario.port().activateUser(ADMIN, "member"));
      // then
      assertThat(failure).isInstanceOf(RuntimeException.class);
      assertThat(scenario.user("member").active()).isFalse();
      assertThat(scenario.audit("user.activate")).isEmpty();
    }

    @Test
    @DisplayName("역할 감사 실패는 기존 구성원 역할을 보존한다")
    void roleChangeRollsBackWhenAuditFails() {
      // given
      scenario.failAudit("user.role");
      // when
      var failure = catchThrowable(() -> scenario.port().changeRole(ADMIN, "member", Role.ADMIN));
      // then
      assertThat(failure).isInstanceOf(RuntimeException.class);
      assertThat(scenario.user("member").role()).isEqualTo(Role.MEMBER);
      assertThat(scenario.audit("user.role")).isEmpty();
    }
  }

  private void assertFailure(Throwable failure, String code) {
    assertThat(failure).isInstanceOf(AdminPort.Failure.class);
    assertThat(((AdminPort.Failure) failure).code()).isEqualTo(code);
    assertThat(failure.getMessage()).isEqualTo(code);
  }

  /** 저장·인증과 실제 포트의 배선, 장애·경합을 한곳에서 소유한다. */
  public abstract static class Scenario {
    private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
    protected static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    protected static final DomainPolicy POLICY = new DomainPolicy(Set.of("example.com"), Map.of());
    private final AdminStore store;
    private final UserTokens authentication;
    private final AdminStore serviceStore = new FaultingStore();
    private String failedAction = "";

    protected Scenario(AdminStore store, UserTokens authentication) {
      this.store = store;
      this.authentication = authentication;
    }

    protected final AdminStore serviceStore() {
      return serviceStore;
    }

    public abstract AdminPort port();

    public final void givenUser(String id, String email, boolean active, Role role) {
      store.transact(
          session -> {
            session.insertUser(new UserAccount(id, email, active, role, AT));
            return true;
          });
    }

    public final void givenSecondAdmin() {
      givenUser("second-admin", "second-admin@example.com", true, Role.ADMIN);
    }

    public final void givenActive(String id, boolean active) {
      if (!store.transact(session -> session.setActive(id, active)))
        throw new IllegalStateException("given 준비 실패: 사용자 상태가 바뀌지 않음");
    }

    public final void givenTwoMemberTokens() {
      authentication.store(TokenHash.of("member-work"), "member", "work", AT);
      authentication.store(TokenHash.of("member-home"), "member", "home", AT);
      if (authentication.authenticate(TokenHash.of("member-work")).isEmpty()
          || authentication.authenticate(TokenHash.of("member-home")).isEmpty())
        throw new IllegalStateException("given 준비 실패: 활성 토큰 없음");
    }

    public final void givenDeactivatedMemberWithTokens() {
      givenTwoMemberTokens();
      port().deactivateUser(ADMIN, "member");
      if (user("member").active()
          || authenticates("member-work").isPresent()
          || authenticates("member-home").isPresent())
        throw new IllegalStateException("given 준비 실패: 비활성화되지 않음");
    }

    public final Optional<Principal> authenticates(String secret) {
      return authentication.authenticate(TokenHash.of(secret));
    }

    public final List<UserAccount> users() {
      return users(ADMIN);
    }

    public final List<UserAccount> users(Principal actor) {
      return port().users(actor, new PageRequest()).items();
    }

    public final UserAccount user(String id) {
      return user(ADMIN, id);
    }

    public final UserAccount user(Principal actor, String id) {
      return users(actor).stream().filter(user -> user.id().equals(id)).findFirst().orElseThrow();
    }

    public final List<AuditEvent> audit(String action) {
      return store
          .transact(
              session ->
                  session.audit(
                      new AuditQuery(
                          50,
                          Optional.empty(),
                          Optional.of(action),
                          Optional.empty(),
                          Optional.empty(),
                          Optional.empty())))
          .items();
    }

    public final void failAudit(String action) {
      failedAction = action;
    }

    public final DemotionRace holdDemotion() {
      return new DemotionRace();
    }

    private final class FaultingStore implements AdminStore {
      @Override
      public <T> T transact(Function<Session, T> work) {
        return store.transact(
            session ->
                work.apply(
                    (Session)
                        Proxy.newProxyInstance(
                            Session.class.getClassLoader(),
                            new Class<?>[] {Session.class},
                            (proxy, method, arguments) -> invoke(session, method, arguments))));
      }

      private Object invoke(Session session, Method method, Object[] arguments) throws Throwable {
        if (method.getName().equals("appendAudit")
            && arguments[0] instanceof NewAuditEvent event
            && event.action().equals(failedAction)) {
          arguments[0] = invalidActor(event);
        }
        try {
          return method.invoke(session, arguments);
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }

      @SuppressWarnings("NullAway")
      private NewAuditEvent invalidActor(NewAuditEvent event) {
        return new NewAuditEvent(
            event.at(), null, event.action(), event.targetType(), event.targetId(), event.detail());
      }
    }

    public final class DemotionRace implements AutoCloseable {
      private static final Duration WAIT = Duration.ofSeconds(5);
      private static final Duration PENDING = Duration.ofMillis(200);
      private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
      private final CountDownLatch held = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final CountDownLatch requested = new CountDownLatch(1);
      private final Future<Boolean> demotion;
      private Future<Throwable> mutation;

      private DemotionRace() {
        demotion =
            workers.submit(
                () ->
                    store.transact(
                        session -> {
                          session.lockAdministration();
                          session.setRole("admin", Role.MEMBER);
                          held.countDown();
                          await(release);
                          return true;
                        }));
        await(held);
      }

      public void requestCreation() {
        mutation =
            workers.submit(
                () -> {
                  requested.countDown();
                  return catchThrowable(
                      () -> port().createUser(ADMIN, "alice@example.com", Role.MEMBER));
                });
        await(requested);
      }

      public void awaitWhileDemotionPending() throws Exception {
        mutation.get(PENDING.toMillis(), TimeUnit.MILLISECONDS);
      }

      public void commitDemotion() throws Exception {
        release.countDown();
        demotion.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      }

      public Throwable mutationFailure() throws Exception {
        return mutation.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      }

      private void await(CountDownLatch gate) {
        try {
          if (!gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Administration race timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }

      @Override
      public void close() {
        release.countDown();
        workers.close();
      }
    }
  }
}
