package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.internal.access.AccessResolver;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
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

/** 실제 관리 포트와 읽기 저장 경계를 같은 상태에 연결하여 메모리·PG에서 실행한다. */
@DisplayName("수동 신원 관리는 현재 권한·관측·원자적 감사를 지킨다(§7.1·U4)")
public abstract class AdminIdentitiesContract {
  private static final Principal ADMIN = new Principal("admin");
  private static final Principal MEMBER = new Principal("alice");
  private static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  protected Scenario scenario;

  protected abstract Scenario newScenario();

  @BeforeEach
  void setUpIdentities() {
    scenario = newScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("alice", "alice@example.com", true, Role.MEMBER);
    scenario.givenUser("bob", "bob@example.com", true, Role.MEMBER);
    scenario.registerSource(new SourceAccount(SLACK, "slack"));
  }

  @Nested
  @DisplayName("등록된 원천의 정회원을 관측한 상태에서")
  class ObservedMember {
    @BeforeEach
    void observeMember() {
      scenario.givenObservation(SLACK, "U1", false, false);
    }

    @Test
    @DisplayName("구성원이 연결하면 forbidden이고, 연결은 생기지 않는다")
    void memberCannotLinkIdentity() {
      // when
      var failure =
          catchThrowable(() -> scenario.port().linkIdentity(MEMBER, "alice", SLACK, "U1"));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.links()).isEmpty();
    }

    @Test
    @DisplayName("관리자가 연결하면 다음 해석에 user 키가 생긴다")
    void manualLinkImmediatelyAddsObservedUserKey() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.resolve("alice").keys())
          .extracting(Object::toString)
          .containsExactly("user:slack-a:U1");
    }

    @Test
    @DisplayName("관측된 정회원을 연결하면 경고가 없다")
    void observedManualLinkHasNoWarning() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link().warnings()).isEmpty();
    }

    @Test
    @DisplayName("수동 연결하면 actor와 admin 방법을 감사한다")
    void manualLinkAuditsActorAndMethod() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link())
          .isEqualTo(
              new IdentityLink(
                  SLACK,
                  "U1",
                  "alice",
                  LinkMethod.ADMIN,
                  Instant.parse("2026-11-08T00:00:00Z"),
                  "admin",
                  Set.of()));
      assertThat(scenario.audit("identity.link"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::detail)
          .containsExactly(
              "admin",
              "identity.link",
              "identity",
              "slack-a:U1",
              Map.of("userId", "alice", "method", "admin"));
    }

    @Test
    @DisplayName("연결 감사가 실패하면 연결·키·감사가 남지 않는다")
    void manualLinkRollsBackWhenAuditFails() {
      // given
      scenario.failAudit("identity.link");
      // when
      var failure = catchThrowable(() -> scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1"));
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Administration storage failed")
          .hasRootCauseMessage("injected identity.link audit failure");
      assertThat(
              List.of(
                  scenario.links(),
                  scenario.resolve("alice").keys(),
                  scenario.audit("identity.link")))
          .containsExactly(List.of(), Set.of(), List.of());
    }

    @Test
    @DisplayName("없는 사용자를 연결하면 not_found이고, 연결이 없다")
    void missingTargetCannotBeLinked() {
      // when
      var failure =
          catchThrowable(() -> scenario.port().linkIdentity(ADMIN, "missing", SLACK, "U1"));
      // then
      assertFailure(failure, "not_found");
      assertThat(scenario.links()).isEmpty();
    }
  }

  @Nested
  @DisplayName("원천 신원을 아직 관측하지 않은 상태에서")
  class Unobserved {
    @Test
    @DisplayName("수동 연결하면 미관측 경고를 반환한다")
    void manualLinkToUnobservedIdentityWarns() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link().warnings()).containsExactly("unobserved");
    }

    @Test
    @DisplayName("미관측 신원을 연결해도 제한 범위에 키가 없다")
    void unobservedManualIdentityGrantsNoKey() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.resolve("alice"))
          .isEqualTo(new AccessScope.Restricted(new Principal("alice"), Set.of()));
    }

    @Test
    @DisplayName("다른 원천에서만 관측해도 미관측 경고를 유지한다")
    void observationInOtherSourceDoesNotClearWarning() {
      // given
      scenario.givenObservationInOtherSource();
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link().warnings()).containsExactly("unobserved");
    }
  }

  @Nested
  @DisplayName("정회원 신원이 수동 연결된 상태에서")
  class ManuallyLinked {
    @BeforeEach
    void linkMember() {
      scenario.givenObservation(SLACK, "U1", false, false);
      scenario.givenLink("alice", SLACK, "U1", LinkMethod.ADMIN);
    }

    @Test
    @DisplayName("구성원이 해제하면 forbidden이고, 연결이 유지된다")
    void memberCannotUnlinkIdentity() {
      // when
      var failure = catchThrowable(() -> scenario.port().unlinkIdentity(MEMBER, SLACK, "U1"));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.link().userId()).isEqualTo("alice");
    }

    @Test
    @DisplayName("해제하면 다음 해석에서 user 키가 사라진다")
    void unlinkImmediatelyRemovesUserKey() {
      // when
      scenario.port().unlinkIdentity(ADMIN, SLACK, "U1");
      // then
      assertThat(scenario.resolve("alice").keys()).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자에게 재연결하면 conflict이고, 기존 연결이 유지된다")
    void crossUserRelinkRequiresUnlink() {
      // when
      var failure = catchThrowable(() -> scenario.port().linkIdentity(ADMIN, "bob", SLACK, "U1"));
      // then
      assertFailure(failure, "conflict");
      assertThat(scenario.link().userId()).isEqualTo("alice");
    }

    @Test
    @DisplayName("해제 감사가 실패하면 연결과 키가 유지된다")
    void unlinkAuditFailureKeepsLink() {
      // given
      scenario.failAudit("identity.unlink");
      // when
      var failure = catchThrowable(() -> scenario.port().unlinkIdentity(ADMIN, SLACK, "U1"));
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Administration storage failed")
          .hasRootCauseMessage("injected identity.unlink audit failure");
      assertThat(
              List.of(
                  scenario.link().userId(),
                  scenario.keys("alice"),
                  scenario.audit("identity.unlink")))
          .containsExactly("alice", List.of("user:slack-a:U1"), List.of());
    }

    @Test
    @DisplayName("같은 수동 연결 요청은 기존 시각·actor를 유지하고, 감사하지 않는다")
    void repeatedManualLinkIsUnchanged() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link())
          .isEqualTo(
              new IdentityLink(
                  SLACK,
                  "U1",
                  "alice",
                  LinkMethod.ADMIN,
                  Instant.parse("2026-11-01T00:00:00Z"),
                  "fixture",
                  Set.of()));
      assertThat(scenario.audit("identity.link")).isEmpty();
    }

    @Test
    @DisplayName("새 관측이 게스트이면 저장된 연결을 바꾸지 않고, 경고한다")
    void warningsFollowCurrentObservation() {
      // when
      scenario.givenObservation(SLACK, "U1", true, false);
      // then
      assertThat(scenario.link().warnings()).containsExactly("guest");
      assertThat(scenario.audit("identity.link")).isEmpty();
    }

    @Test
    @DisplayName("관측이 게스트이자 탈퇴이면 두 경고를 반환한다")
    void guestAndDeletedWarningsAreCombined() {
      // when
      scenario.givenObservation(SLACK, "U1", true, true);
      // then
      assertThat(scenario.link().warnings()).containsExactlyInAnyOrder("guest", "deleted");
    }

    @Test
    @DisplayName("관리자가 연결 목록을 요청하면 사용자 필터로 제한한다")
    void linksFilterByUser() {
      // given
      scenario.givenLink("bob", SLACK, "U2", LinkMethod.ADMIN);
      // when
      var page = scenario.port().links(ADMIN, Optional.of("bob"), new PageRequest());
      // then
      assertThat(page.items()).extracting(IdentityLink::sourceUserId).containsExactly("U2");
    }

    @Test
    @DisplayName("연결 커서로 다음 페이지를 조회하면 경고와 순서를 유지한다")
    void linksCursorPreservesOrderAndWarnings() {
      // given
      scenario.givenLink("bob", SLACK, "U2", LinkMethod.ADMIN);
      var first =
          scenario.port().links(ADMIN, Optional.empty(), new PageRequest(1, Optional.empty()));
      // when
      var next =
          scenario.port().links(ADMIN, Optional.empty(), new PageRequest(1, first.nextCursor()));
      // then
      assertThat(next.items())
          .singleElement()
          .extracting(IdentityLink::sourceUserId, IdentityLink::warnings)
          .containsExactly("U2", Set.of("unobserved"));
      assertThat(next.nextCursor()).isEmpty();
    }
  }

  @Nested
  @DisplayName("적격 정회원 신원이 자동 연결된 상태에서")
  class AutomaticallyLinked {
    @BeforeEach
    void linkMember() {
      scenario.givenObservation(SLACK, "U1", false, false);
      scenario.givenLink("alice", SLACK, "U1", LinkMethod.AUTO);
    }

    @Test
    @DisplayName("수동 해제하면 현재 연결을 삭제하고, 한 번 감사한다")
    void adminUnlinkRemovesCurrentEligibleAutoLink() {
      // when
      scenario.port().unlinkIdentity(ADMIN, SLACK, "U1");
      // then
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.audit("identity.unlink"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::detail)
          .containsExactly(
              "admin",
              "identity.unlink",
              "identity",
              "slack-a:U1",
              Map.of("userId", "alice", "method", "auto"));
    }

    @Test
    @DisplayName("같은 사용자의 수동 요청은 auto를 admin으로 바꾸고, 감사한다")
    void explicitManualLinkReplacesAutoMethod() {
      // when
      scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
      // then
      assertThat(scenario.link())
          .extracting(IdentityLink::method, IdentityLink::linkedBy)
          .containsExactly(LinkMethod.ADMIN, "admin");
      assertThat(scenario.audit("identity.link"))
          .singleElement()
          .extracting(AuditEvent::detail)
          .isEqualTo(Map.of("userId", "alice", "method", "admin"));
    }
  }

  @Test
  @DisplayName("게스트를 연결하면 연결과 경고가 있고, 키는 없다")
  void guestManualLinkIsKeptWithoutKeys() {
    // given
    scenario.givenObservation(SLACK, "U1", true, false);
    // when
    scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
    // then
    assertThat(scenario.link())
        .extracting(IdentityLink::userId, IdentityLink::warnings)
        .containsExactly("alice", Set.of("guest"));
    assertThat(scenario.resolve("alice").keys()).isEmpty();
  }

  @Test
  @DisplayName("탈퇴자를 연결하면 연결과 경고가 있고, 키는 없다")
  void deletedManualLinkIsKeptWithoutKeys() {
    // given
    scenario.givenObservation(SLACK, "U1", false, true);
    // when
    scenario.port().linkIdentity(ADMIN, "alice", SLACK, "U1");
    // then
    assertThat(scenario.link())
        .extracting(IdentityLink::userId, IdentityLink::warnings)
        .containsExactly("alice", Set.of("deleted"));
    assertThat(scenario.resolve("alice").keys()).isEmpty();
  }

  @Test
  @DisplayName("미관측 연결 뒤 정회원이 관측되면 조회 경고가 사라진다")
  void observationClearsUnobservedWarning() {
    // given
    scenario.givenLink("alice", SLACK, "U1", LinkMethod.ADMIN);
    // when
    scenario.givenObservation(SLACK, "U1", false, false);
    // then
    assertThat(scenario.link().warnings()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"link", "unlink"})
  @DisplayName("미등록 원천을 변경하면 not_found이고, 연결이 없다")
  void unregisteredSourceIsNotFound(String operation) {
    // when
    var failure =
        catchThrowable(() -> scenario.change(operation, new SourceInstanceId("missing-a")));
    // then
    assertFailure(failure, "not_found");
    assertThat(scenario.links()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"link", "unlink", "list"})
  @DisplayName("현재 관리자가 비활성이면 forbidden이다")
  void inactiveAdministratorCannotManageIdentities(String operation) {
    // given
    scenario.givenActive("admin", false);
    // when
    var failure = catchThrowable(() -> scenario.change(operation, SLACK));
    // then
    assertFailure(failure, "forbidden");
    assertThat(scenario.audit("identity.link")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"link", "unlink", "list"})
  @DisplayName("관리자가 강등되면 다음 요청은 forbidden이다")
  void demotedAdministratorCannotManageIdentities(String operation) {
    // given
    scenario.givenRole("admin", Role.MEMBER);
    // when
    var failure = catchThrowable(() -> scenario.change(operation, SLACK));
    // then
    assertFailure(failure, "forbidden");
  }

  @Test
  @DisplayName("구성원이 연결 목록을 조회하면 forbidden이다")
  void memberCannotListIdentityLinks() {
    // when
    var failure =
        catchThrowable(() -> scenario.port().links(MEMBER, Optional.empty(), new PageRequest()));
    // then
    assertFailure(failure, "forbidden");
  }

  @Test
  @DisplayName("잘못된 연결 커서를 요청하면 invalid_request다")
  void malformedLinkCursorIsInvalidRequest() {
    // when
    var failure =
        catchThrowable(
            () ->
                scenario
                    .port()
                    .links(
                        ADMIN, Optional.empty(), new PageRequest(50, Optional.of("not-a-cursor"))));
    // then
    assertFailure(failure, "invalid_request");
  }

  @Test
  @DisplayName("없는 연결을 해제하면 감사하지 않는다")
  void absentUnlinkIsUnchanged() {
    // when
    scenario.port().unlinkIdentity(ADMIN, SLACK, "U1");
    // then
    assertThat(scenario.audit("identity.unlink")).isEmpty();
  }

  @Test
  @DisplayName("명시 해제된 신원은 다른 사용자에게 연결할 수 있다")
  void unlinkedIdentityCanMoveToAnotherUser() {
    // given
    scenario.givenUnlinkedAlice();
    // when
    scenario.port().linkIdentity(ADMIN, "bob", SLACK, "U1");
    // then
    assertThat(scenario.link().userId()).isEqualTo("bob");
  }

  private void assertFailure(Throwable failure, String code) {
    assertThat(failure).isInstanceOf(AdminPort.Failure.class);
    assertThat(((AdminPort.Failure) failure).code()).isEqualTo(code);
  }

  /** 관측·저장·해석 배선과 action별 감사 장애를 소유한다. */
  public abstract static class Scenario {
    private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
    protected static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    protected static final DomainPolicy POLICY = new DomainPolicy(Set.of("example.com"), Map.of());
    private final AdminStore store;
    private final AccessResolver resolver;
    private String failedAction = "";

    protected Scenario(AdminStore store, IdentityStore identities) {
      this.store = store;
      resolver = new AccessResolver(identities, POLICY);
    }

    public abstract AdminPort port();

    public abstract void registerSource(SourceAccount source);

    protected abstract void observe(SourceInstanceId source, SourceIdentityRecord identity);

    public final void givenObservation(
        SourceInstanceId source, String id, boolean guest, boolean deleted) {
      observe(
          source,
          new SourceIdentityRecord(id, Optional.of("alice@example.com"), true, guest, deleted, AT));
    }

    public final void givenObservationInOtherSource() {
      var other = new SourceInstanceId("slack-b");
      registerSource(new SourceAccount(other, "slack"));
      givenObservation(other, "U1", false, false);
    }

    public final void givenUser(String id, String email, boolean active, Role role) {
      store.transact(
          session -> {
            session.insertUser(new UserAccount(id, email, active, role, AT));
            return true;
          });
    }

    public final void givenActive(String id, boolean active) {
      if (!store.transact(session -> session.setActive(id, active)))
        throw new IllegalStateException("given 준비 실패: 사용자 상태 불변");
    }

    public final void givenRole(String id, Role role) {
      if (!store.transact(session -> session.setRole(id, role)))
        throw new IllegalStateException("given 준비 실패: 사용자 역할 불변");
    }

    public final void givenLink(
        String user, SourceInstanceId source, String id, LinkMethod method) {
      store.transact(
          session ->
              session.putLink(
                  new IdentityLink(
                      source,
                      id,
                      user,
                      method,
                      Instant.parse("2026-11-01T00:00:00Z"),
                      "fixture",
                      Set.of())));
    }

    public final void givenUnlinkedAlice() {
      givenLink("alice", SLACK, "U1", LinkMethod.ADMIN);
      port().unlinkIdentity(ADMIN, SLACK, "U1");
      if (!links().isEmpty()) throw new IllegalStateException("given 준비 실패: 연결이 남음");
    }

    public final void change(String operation, SourceInstanceId source) {
      switch (operation) {
        case "link" -> port().linkIdentity(ADMIN, "alice", source, "U1");
        case "unlink" -> port().unlinkIdentity(ADMIN, source, "U1");
        case "list" -> port().links(ADMIN, Optional.empty(), new PageRequest());
        default -> throw new IllegalArgumentException("Unknown operation");
      }
    }

    public final List<IdentityLink> links() {
      return port().links(ADMIN, Optional.empty(), new PageRequest()).items();
    }

    public final IdentityLink link() {
      var links = links();
      if (links.size() != 1) throw new IllegalStateException("given 준비 실패: 단일 연결이 아님");
      return links.getFirst();
    }

    public final List<String> keys(String user) {
      return resolve(user).keys().stream().map(Object::toString).toList();
    }

    public final AccessScope.Restricted resolve(String user) {
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

    protected final AdminStore serviceStore() {
      return new FailingAuditStore();
    }

    private final class FailingAuditStore implements AdminStore {
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
