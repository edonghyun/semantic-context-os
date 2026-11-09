package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.internal.access.AccessResolver;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 실제 관리 포트의 지정·해제를 메모리·PG의 동일 읽기 경계에서 확인한다. */
@DisplayName("조직 전체 지정은 Notion만 허용하고 즉시 회수한다(§7.1)")
public abstract class AdminScopesContract {
  protected static final Principal ADMIN = new Principal("admin");
  protected static final SourceInstanceId NOTION = new SourceInstanceId("notion-a");
  private static final Principal MEMBER = new Principal("alice");
  protected Scenario scenario;

  protected abstract Scenario newScenario();

  @BeforeEach
  void setUpScopes() {
    scenario = newScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("alice", "alice@example.com", true, Role.MEMBER);
    scenario.registerSource(new SourceAccount(NOTION, "notion"));
  }

  @Test
  @DisplayName("원천 잠금 획득이 실패하면 조직 지정과 감사를 남기지 않는다")
  void sourceLockFailurePreventsDesignation() {
    scenario.failNextSourceLock();
    // when
    var failure = catchThrowable(() -> scenario.port().designateScope(ADMIN, NOTION));
    // then
    assertStorageFailure(failure, "injected source lock failure");
    assertThat(scenario.scopes()).isEmpty();
    assertThat(scenario.audit("scope.designate")).isEmpty();
  }

  @Test
  @DisplayName("다른 Notion의 기존 지정이 있어도 요청한 원천을 새로 지정한다")
  void existingOtherScopeDoesNotReplaceRequestedSource() {
    var other = new SourceInstanceId("notion-b");
    scenario.registerSource(new SourceAccount(other, "notion"));
    scenario.givenDesignation(other);
    // when
    var result = scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(result.source().value()).isEqualTo("notion-a");
    assertThat(scenario.scopes())
        .extracting(scope -> scope.source().value())
        .containsExactly("notion-a", "notion-b");
    assertThat(scenario.audit("scope.designate"))
        .singleElement()
        .extracting(AuditEvent::targetId)
        .isEqualTo("notion-a");
  }

  @Test
  @DisplayName("구성원이 지정하면 forbidden이고, 지정은 생기지 않는다")
  void memberCannotDesignateScope() {
    // when
    var failure = catchThrowable(() -> scenario.port().designateScope(MEMBER, NOTION));
    // then
    assertFailure(failure, "forbidden");
    assertThat(scenario.scopes()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"slack", "google-drive", "jira", "tiro", "unknown", "Notion"})
  @DisplayName("Notion 이외 종류를 지정하면 invalid_request이고, 감사가 없다")
  void nonNotionSourceCannotBeDesignated(String kind) {
    // given
    var other = new SourceInstanceId("other-a");
    scenario.registerSource(new SourceAccount(other, kind));
    // when
    var failure = catchThrowable(() -> scenario.port().designateScope(ADMIN, other));
    // then
    assertFailure(failure, "invalid_request");
    assertThat(scenario.scopes()).isEmpty();
    assertThat(scenario.audit("scope.designate")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"designate", "revoke"})
  @DisplayName("미등록 원천을 변경하면 not_found이고, 지정은 생기지 않는다")
  void unknownSourceCannotBeDesignated(String operation) {
    // when
    var failure =
        catchThrowable(() -> scenario.change(operation, new SourceInstanceId("missing-a")));
    // then
    assertFailure(failure, "not_found");
    assertThat(scenario.scopes()).isEmpty();
  }

  @Test
  @DisplayName("지정하면 신원 연결 없는 활성 사용자에게 scope 키가 생긴다")
  void designationImmediatelyAddsScopeForActiveUser() {
    // when
    scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(scenario.keys("alice")).containsExactly("scope:notion-a:*");
  }

  @Test
  @DisplayName("지정하면 비활성 사용자에게는 접근 키를 주지 않는다")
  void designationDoesNotGrantInactiveUser() {
    // given
    scenario.givenUser("inactive", "inactive@example.com", false, Role.MEMBER);
    // when
    scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(scenario.keys("inactive")).isEmpty();
  }

  @Test
  @DisplayName("지정하면 지정 주체와 시각을 반환하고, 저장한다")
  void designationReturnsCommittedMetadata() {
    // when
    var designation = scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(designation)
        .isEqualTo(new ScopeDesignation(NOTION, "admin", Instant.parse("2026-11-08T00:00:00Z")));
    assertThat(scenario.scopes()).containsExactly(designation);
  }

  @Test
  @DisplayName("지정하면 원천의 하위 제한도 포함한다는 경고를 반환한다")
  void designationReturnsBroadVisibilityWarning() {
    // when
    var designation = scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(designation.visibilityWarning())
        .isEqualTo("이 소스의 모든 페이지가 전원에게 보입니다. 원천에서 제한한 하위 페이지도 포함됩니다.");
  }

  @Test
  @DisplayName("지정하면 요청자와 원천을 한 번 감사한다")
  void scopeDesignationIsAudited() {
    // when
    scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(scenario.audit("scope.designate"))
        .singleElement()
        .extracting(
            AuditEvent::actor,
            AuditEvent::action,
            AuditEvent::targetType,
            AuditEvent::targetId,
            AuditEvent::at)
        .containsExactly(
            "admin", "scope.designate", "scope", "notion-a", Instant.parse("2026-11-08T00:00:00Z"));
  }

  @Test
  @DisplayName("지정 감사가 실패하면 지정과 접근 키를 남기지 않는다")
  void designationRollsBackWhenAuditFails() {
    // given
    scenario.failAudit("scope.designate");
    // when
    var failure = catchThrowable(() -> scenario.port().designateScope(ADMIN, NOTION));
    // then
    assertStorageFailure(failure, "injected scope.designate audit failure");
    assertThat(scenario.scopes()).isEmpty();
    assertThat(List.of(scenario.keys("alice"), scenario.audit("scope.designate")))
        .containsExactly(List.of(), List.of());
  }

  @Test
  @DisplayName("기존 미지원 문서를 지정하면 ACL 변경 없이 보인다")
  void unsupportedDocumentBecomesVisibleWithoutReprojection() {
    // given
    scenario.givenUnsupportedDocument();
    // when
    scenario.port().designateScope(ADMIN, NOTION);
    // then
    assertThat(scenario.documentVisibleTo("alice")).isTrue();
    assertThat(scenario.documentAcl())
        .extracting(DocumentAcl::observedAt, DocumentAcl::fingerprint)
        .containsExactly(
            Instant.parse("2026-11-01T00:00:00Z"),
            "9c62ebb421d9e35a758acab2470df4cdb8c872c656324c6b620200c8c6982c96");
  }

  @Nested
  @DisplayName("Notion이 이미 조직 전체로 지정된 상태에서")
  class Designated {
    @BeforeEach
    void givenDesignation() {
      scenario.givenDesignation(NOTION);
    }

    @Test
    @DisplayName("구성원이 해제하면 forbidden이고, 지정은 유지된다")
    void memberCannotRevokeScope() {
      // when
      var failure = catchThrowable(() -> scenario.port().revokeScope(MEMBER, NOTION));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.scopes())
          .containsExactly(
              new ScopeDesignation(NOTION, "fixture", Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    @DisplayName("해제하면 다음 해석에서 scope 키가 사라진다")
    void revocationImmediatelyRemovesScope() {
      // given
      scenario.requireVisibleScope();
      // when
      scenario.port().revokeScope(ADMIN, NOTION);
      // then
      assertThat(scenario.keys("alice")).isEmpty();
      assertThat(scenario.scopes()).isEmpty();
    }

    @Test
    @DisplayName("해제하면 요청자와 원천을 한 번 감사한다")
    void scopeRevocationIsAudited() {
      // when
      scenario.port().revokeScope(ADMIN, NOTION);
      // then
      assertThat(scenario.audit("scope.revoke"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::at)
          .containsExactly(
              "admin", "scope.revoke", "scope", "notion-a", Instant.parse("2026-11-08T00:00:00Z"));
    }

    @Test
    @DisplayName("해제 감사가 실패하면 원래 지정과 접근 키가 유지된다")
    void scopeRevocationRollsBackWhenAuditFails() {
      // given
      scenario.failAudit("scope.revoke");
      // when
      var failure = catchThrowable(() -> scenario.port().revokeScope(ADMIN, NOTION));
      // then
      assertStorageFailure(failure, "injected scope.revoke audit failure");
      assertThat(scenario.scopes())
          .containsExactly(
              new ScopeDesignation(NOTION, "fixture", Instant.parse("2026-11-01T00:00:00Z")));
      assertThat(List.of(scenario.keys("alice"), scenario.audit("scope.revoke")))
          .containsExactly(List.of("scope:notion-a:*"), List.of());
    }

    @Test
    @DisplayName("같은 지정을 반복하면 최초 주체와 시각을 반환한다")
    void repeatedDesignationPreservesMetadata() {
      // when
      var designation = scenario.port().designateScope(ADMIN, NOTION);
      // then
      assertThat(designation)
          .isEqualTo(
              new ScopeDesignation(NOTION, "fixture", Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    @DisplayName("다시 지정하면 최초 감사만 남는다")
    void repeatedDesignationDoesNotDuplicateAudit() {
      // given
      scenario.givenFreshDesignation();
      // when
      scenario.port().designateScope(ADMIN, NOTION);
      // then
      assertThat(scenario.audit("scope.designate")).hasSize(1);
    }

    @Test
    @DisplayName("지정 해제하면 기존 문서가 ACL 변경 없이 숨겨진다")
    void revokedScopeHidesExistingDocument() {
      // given
      scenario.givenUnsupportedDocument();
      scenario.requireVisibleDocument();
      // when
      scenario.port().revokeScope(ADMIN, NOTION);
      // then
      assertThat(scenario.documentVisibleTo("alice")).isFalse();
      assertThat(scenario.documentAcl())
          .extracting(DocumentAcl::observedAt, DocumentAcl::fingerprint)
          .containsExactly(
              Instant.parse("2026-11-01T00:00:00Z"),
              "9c62ebb421d9e35a758acab2470df4cdb8c872c656324c6b620200c8c6982c96");
    }
  }

  @Test
  @DisplayName("없는 지정을 해제하면 감사하지 않는다")
  void absentRevocationIsUnchanged() {
    // when
    scenario.port().revokeScope(ADMIN, NOTION);
    // then
    assertThat(scenario.audit("scope.revoke")).isEmpty();
  }

  @Test
  @DisplayName("다른 Notion 인스턴스를 해제하면 기존 지정은 유지된다")
  void revokingOtherInstanceKeepsDesignation() {
    // given
    scenario.givenDesignation(NOTION);
    var other = new SourceInstanceId("notion-b");
    scenario.registerSource(new SourceAccount(other, "notion"));
    // when
    scenario.port().revokeScope(ADMIN, other);
    // then
    assertThat(scenario.keys("alice")).containsExactly("scope:notion-a:*");
    assertThat(scenario.audit("scope.revoke")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"designate", "revoke", "list"})
  @DisplayName("현재 관리자가 비활성이면 forbidden이다")
  void inactiveAdministratorCannotManageScopes(String operation) {
    // given
    scenario.givenInactiveAdministrator();
    // when
    var failure = catchThrowable(() -> scenario.change(operation, NOTION));
    // then
    assertFailure(failure, "forbidden");
  }

  @ParameterizedTest
  @ValueSource(strings = {"designate", "revoke", "list"})
  @DisplayName("현재 관리자가 강등되면 forbidden이다")
  void demotedAdministratorCannotManageScopes(String operation) {
    // given
    scenario.givenDemotedAdministrator();
    // when
    var failure = catchThrowable(() -> scenario.change(operation, NOTION));
    // then
    assertFailure(failure, "forbidden");
  }

  @Test
  @DisplayName("구성원이 지정 목록을 조회하면 forbidden이다")
  void memberCannotListScopes() {
    // when
    var failure = catchThrowable(() -> scenario.port().scopes(MEMBER));
    // then
    assertFailure(failure, "forbidden");
  }

  private void assertFailure(Throwable failure, String code) {
    assertThat(failure)
        .isInstanceOf(AdminPort.Failure.class)
        .extracting(error -> ((AdminPort.Failure) error).code())
        .isEqualTo(code);
  }

  private void assertStorageFailure(Throwable failure, String cause) {
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Administration storage failed")
        .hasRootCauseMessage(cause);
  }

  /** 저장·해석·기존 문서와 action별 감사 장애를 같은 조립에 둔다. */
  public abstract static class Scenario {
    protected static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
    protected static final DomainPolicy POLICY = new DomainPolicy(Set.of("example.com"), Map.of());
    private static final SpaceId SPACE = new SpaceId("scope-contract");
    private final AdminStore store;
    private final LockFailureStore lockFailures;
    private final AccessResolver resolver;
    private final AclStore acls;
    private String failedAction = "";

    protected Scenario(AdminStore store, IdentityStore identities, AclStore acls) {
      this.store = store;
      lockFailures = new LockFailureStore(store);
      resolver = new AccessResolver(identities, POLICY);
      this.acls = acls;
    }

    public abstract AdminPort port();

    public abstract void registerSource(SourceAccount source);

    public final void givenUser(String id, String email, boolean active, Role role) {
      store.transact(
          session -> {
            session.insertUser(new UserAccount(id, email, active, role, CLOCK.instant()));
            return true;
          });
    }

    public final void givenDesignation(SourceInstanceId source) {
      store.transact(
          session ->
              session.designate(
                  new ScopeDesignation(source, "fixture", Instant.parse("2026-11-01T00:00:00Z"))));
    }

    public final void givenFreshDesignation() {
      store.transact(session -> session.revokeScope(NOTION));
      port().designateScope(ADMIN, NOTION);
      if (audit("scope.designate").size() != 1)
        throw new IllegalStateException("given 준비 실패: 최초 지정 감사가 없음");
    }

    public final void givenInactiveAdministrator() {
      store.transact(session -> session.setActive("admin", false));
    }

    public final void givenDemotedAdministrator() {
      store.transact(session -> session.setRole("admin", Role.MEMBER));
    }

    public final void givenUnsupportedDocument() {
      acls.put(
          SPACE,
          List.of(
              new DocumentAcl(
                  "notion-page",
                  DocumentAcl.Status.UNSUPPORTED,
                  Set.of(AccessKey.parse("scope:notion-a:*")),
                  Instant.parse("2026-11-01T00:00:00Z"))));
    }

    public final DocumentAcl documentAcl() {
      return acls.get(SPACE, List.of("notion-page")).get("notion-page");
    }

    public final boolean documentVisibleTo(String user) {
      return documentAcl().visibleTo(resolve(user).keys());
    }

    public final void requireVisibleScope() {
      if (!keys("alice").equals(List.of("scope:notion-a:*")))
        throw new IllegalStateException("given 준비 실패: scope 키가 없음");
    }

    public final void requireVisibleDocument() {
      if (!documentVisibleTo("alice"))
        throw new IllegalStateException("given 준비 실패: 기존 문서가 보이지 않음");
    }

    public final void change(String operation, SourceInstanceId source) {
      switch (operation) {
        case "designate" -> port().designateScope(ADMIN, source);
        case "revoke" -> port().revokeScope(ADMIN, source);
        case "list" -> port().scopes(ADMIN);
        default -> throw new IllegalArgumentException("Unknown operation");
      }
    }

    public final List<ScopeDesignation> scopes() {
      return port().scopes(ADMIN);
    }

    public final List<String> keys(String user) {
      return resolve(user).keys().stream().map(Object::toString).toList();
    }

    private AccessScope.Restricted resolve(String user) {
      return (AccessScope.Restricted) resolver.resolve(new Principal(user));
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

    public final void failNextSourceLock() {
      lockFailures.failNextSourceLock();
    }

    protected final AdminStore serviceStore() {
      return new FailingAuditStore();
    }

    private final class FailingAuditStore implements AdminStore {
      @Override
      public <T> T transact(Function<Session, T> work) {
        return lockFailures.transact(
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
            && event.action().equals(failedAction))
          throw new IllegalStateException("injected " + failedAction + " audit failure");
        try {
          return method.invoke(session, arguments);
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }
    }
  }
}
