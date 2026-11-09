package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.TokenMetadata;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 같은 실제 포트 계약을 메모리와 PostgreSQL에서 실행한다. */
@DisplayName("토큰 관리는 본인 예외와 원자적 감사를 보장한다(§6.1·§7.1)")
public abstract class AdminTokensContract {
  private static final Principal ADMIN = new Principal("admin");
  private static final Principal MEMBER = new Principal("member");
  protected Scenario scenario;

  protected abstract Scenario newScenario();

  @BeforeEach
  void setUpTokens() {
    scenario = newScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("member", "member@example.com", true, Role.MEMBER);
  }

  @Test
  @DisplayName("관리 잠금 획득이 실패하면 토큰 폐기를 시작하지 않는다")
  void revocationLockFailureKeepsTokenUsable() {
    var issued = scenario.givenMemberToken();
    scenario.failNextAdministrationLock();
    // when
    var failure = catchThrowable(() -> scenario.port().revokeToken(MEMBER, issued.tokenId()));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("injected administration lock failure");
    assertThat(scenario.authenticates(issued.value())).contains(new Principal("member"));
    assertThat(scenario.audit("token.revoke")).isEmpty();
  }

  @Test
  @DisplayName("마지막 관리자 토큰 폐기는 conflict이고 토큰과 감사는 불변이다")
  void lastAdministratorTokenRevocationConflicts() {
    var issued = scenario.givenAdminToken();
    // when
    var failure = catchThrowable(() -> scenario.port().revokeToken(ADMIN, issued.tokenId()));
    // then
    assertFailure(failure, "conflict");
    assertThat(scenario.metadata(ADMIN, "admin").getFirst().revokedAt()).isEmpty();
    assertThat(scenario.audit("token.revoke")).isEmpty();
  }

  @Test
  @DisplayName("마지막 관리자 토큰 폐기를 거부한 뒤 같은 토큰으로 인증한다")
  void rejectedLastAdministratorTokenStillAuthenticates() {
    var issued = scenario.givenAdminToken();
    // when
    var failure = catchThrowable(() -> scenario.port().revokeToken(ADMIN, issued.tokenId()));
    // then
    assertFailure(failure, "conflict");
    assertThat(scenario.authenticates(issued.value())).contains(ADMIN);
  }

  @Test
  @DisplayName("다른 활성 관리자가 유효 토큰을 가지면 폐기를 허용한다")
  void revocationAllowedWhenAnotherAdministratorHoldsToken() {
    var issued = scenario.givenAdminToken();
    scenario.givenUser("second-admin", "second@example.com", true, Role.ADMIN);
    var other = scenario.port().issueToken(ADMIN, "second-admin", "work");
    // when
    scenario.port().revokeToken(ADMIN, issued.tokenId());
    // then
    assertThat(scenario.authenticates(issued.value())).isEmpty();
    assertThat(scenario.authenticates(other.value())).contains(new Principal("second-admin"));
    assertThat(scenario.audit("token.revoke")).hasSize(1);
  }

  @Test
  @DisplayName("교체 토큰을 발급했으면 같은 관리자의 이전 토큰을 폐기한다")
  void replacementAdministratorTokenAllowsRevocation() {
    var issued = scenario.givenAdminToken();
    var replacement = scenario.givenAdminToken();
    // when
    scenario.port().revokeToken(ADMIN, issued.tokenId());
    // then
    assertThat(scenario.authenticates(issued.value())).isEmpty();
    assertThat(scenario.authenticates(replacement.value())).contains(ADMIN);
  }

  @Test
  @DisplayName("다른 관리자의 폐기된 토큰은 마지막 토큰을 대체하지 못한다")
  void revokedAdministratorTokenCannotPermitRevocation() {
    var issued = scenario.givenAdminToken();
    scenario.givenUser("second-admin", "second@example.com", true, Role.ADMIN);
    var other = scenario.port().issueToken(ADMIN, "second-admin", "work");
    scenario.port().revokeToken(ADMIN, other.tokenId());
    // when
    var failure = catchThrowable(() -> scenario.port().revokeToken(ADMIN, issued.tokenId()));
    // then
    assertFailure(failure, "conflict");
    assertThat(scenario.authenticates(issued.value())).contains(ADMIN);
    assertThat(scenario.audit("token.revoke")).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({"true,ADMIN,false", "false,ADMIN,true", "true,MEMBER,true"})
  @DisplayName("유효 토큰 없는 관리자·비활성 관리자·구성원은 마지막 토큰을 대체하지 못한다")
  void unusableAdministratorCannotPermitRevocation(boolean active, Role role, boolean token) {
    var issued = scenario.givenAdminToken();
    scenario.givenUser("other", "other@example.com", true, role);
    if (token) scenario.port().issueToken(ADMIN, "other", "work");
    if (!active) scenario.givenActive("other", false);
    // when
    var failure = catchThrowable(() -> scenario.port().revokeToken(ADMIN, issued.tokenId()));
    // then
    assertFailure(failure, "conflict");
    assertThat(scenario.authenticates(issued.value())).contains(ADMIN);
    assertThat(scenario.audit("token.revoke")).isEmpty();
  }

  @Nested
  @DisplayName("활성 구성원이 본인 토큰을 관리할 때")
  class OwnTokens {
    @Test
    @DisplayName("본인 발급 토큰으로 본인 Principal이 인증된다")
    void memberCanIssueOwnToken() {
      // when
      var issued = scenario.port().issueToken(MEMBER, "member", "work");
      // then
      assertThat(scenario.authenticates(issued.value())).contains(new Principal("member"));
    }

    @Test
    @DisplayName("토큰 원문은 SHA-256 해시로만 저장된다")
    void storedTokenIsOnlySha256() {
      // when
      var issued = scenario.port().issueToken(MEMBER, "member", "work");
      // then
      assertThat(scenario.storedHashMatches(issued)).isTrue();
    }

    @Test
    @DisplayName("발급 결과의 문자열은 원문을 숨긴다")
    void tokenStringRepresentationIsRedacted() {
      // when
      var issued = scenario.port().issueToken(MEMBER, "member", "work");
      // then
      assertThat(issued.toString().contains(issued.value())).isFalse();
    }

    @Test
    @DisplayName("본인 메타데이터에는 정규화한 label과 생성 시각이 있다")
    void memberCanListOwnTokenMetadata() {
      // when
      var issued = scenario.port().issueToken(MEMBER, "member", " work ");
      // then
      assertThat(scenario.metadata(MEMBER, "member"))
          .singleElement()
          .extracting(
              TokenMetadata::tokenId,
              TokenMetadata::userId,
              TokenMetadata::label,
              TokenMetadata::createdAt,
              TokenMetadata::lastUsedAt,
              TokenMetadata::revokedAt)
          .containsExactly(
              issued.tokenId(),
              "member",
              "work",
              Instant.parse("2026-11-08T00:00:00Z"),
              Optional.empty(),
              Optional.empty());
    }

    @Test
    @DisplayName("본인 토큰 폐기 뒤 다음 인증은 거부된다")
    void revocationRejectsNextAuthentication() {
      // given
      var issued = scenario.givenMemberToken();
      // when
      scenario.port().revokeToken(MEMBER, issued.tokenId());
      // then
      assertThat(scenario.authenticates(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("둘째 메타데이터 페이지의 토큰도 폐기한다")
    void revocationFindsTokenBeyondFirstMetadataPage() {
      // given
      var issued = scenario.givenMemberTokenOnSecondPage();
      // when
      scenario.port().revokeToken(MEMBER, issued.tokenId());
      // then
      assertThat(scenario.authenticates(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("둘째 페이지의 재폐기도 최초 시각과 감사를 보존한다")
    void repeatedRevocationBeyondFirstPageIsUnchanged() {
      // given
      var issued = scenario.givenRevokedMemberTokenOnSecondPage();
      scenario.advanceClock();
      // when
      scenario.port().revokeToken(MEMBER, issued.tokenId());
      // then
      assertThat(scenario.metadataOf(issued.tokenId()).revokedAt())
          .contains(Instant.parse("2026-11-08T00:00:00Z"));
      assertThat(scenario.audit("token.revoke")).hasSize(1);
    }

    @Test
    @DisplayName("토큰 메타데이터 커서로 다음 토큰을 중복 없이 조회한다")
    void tokenMetadataCursorContinuesWithoutDuplicates() {
      // given
      scenario.givenTwoMemberTokens();
      // when
      var first = scenario.port().tokens(MEMBER, "member", new PageRequest(1, Optional.empty()));
      var second = scenario.port().tokens(MEMBER, "member", new PageRequest(1, first.nextCursor()));
      // then
      assertThat(List.of(first.items().getFirst().tokenId(), second.items().getFirst().tokenId()))
          .doesNotHaveDuplicates();
      assertThat(second.nextCursor()).isEmpty();
    }

    @Test
    @DisplayName("재폐기는 최초 시각을 보존하고, 감사가 늘지 않는다")
    void repeatedRevocationKeepsFirstTimestamp() {
      // given
      var issued = scenario.givenRevokedMemberToken();
      scenario.advanceClock();
      // when
      scenario.port().revokeToken(MEMBER, issued.tokenId());
      // then
      assertThat(scenario.metadata(MEMBER, "member").getFirst().revokedAt())
          .contains(Instant.parse("2026-11-08T00:00:00Z"));
      assertThat(scenario.audit("token.revoke")).hasSize(1);
    }
  }

  @Nested
  @DisplayName("구성원이 타인의 토큰을 요청할 때")
  class OthersTokens {
    @Test
    @DisplayName("타인 토큰 발급은 forbidden이고, 토큰이 생기지 않는다")
    void memberCannotIssueOthersToken() {
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(MEMBER, "admin", "work"));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.metadata(ADMIN, "admin")).isEmpty();
    }

    @Test
    @DisplayName("타인 토큰 폐기는 forbidden이고, 인증이 유지된다")
    void memberCannotRevokeOthersToken() {
      // given
      var issued = scenario.givenAdminToken();
      // when
      var failure = catchThrowable(() -> scenario.port().revokeToken(MEMBER, issued.tokenId()));
      // then
      assertFailure(failure, "forbidden");
      assertThat(scenario.authenticates(issued.value())).contains(new Principal("admin"));
    }

    @Test
    @DisplayName("타인 토큰 메타데이터 조회는 forbidden이다")
    void memberCannotListOthersTokens() {
      // when
      var failure =
          catchThrowable(() -> scenario.port().tokens(MEMBER, "admin", new PageRequest()));
      // then
      assertFailure(failure, "forbidden");
    }
  }

  @Nested
  @DisplayName("현재 사용자 상태와 입력을 검사할 때")
  class Validation {
    @Test
    @DisplayName("관리자는 다른 활성 사용자의 토큰을 발급한다")
    void adminCanIssueOthersToken() {
      // when
      var issued = scenario.port().issueToken(ADMIN, "member", "work");
      // then
      assertThat(scenario.authenticates(issued.value())).contains(new Principal("member"));
    }

    @Test
    @DisplayName("관리자는 다른 사용자의 토큰을 폐기한다")
    void adminCanRevokeOthersToken() {
      // given
      var issued = scenario.givenMemberToken();
      // when
      scenario.port().revokeToken(ADMIN, issued.tokenId());
      // then
      assertThat(scenario.authenticates(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("비활성 구성원의 본인 발급은 forbidden이다")
    void inactiveMemberCannotIssueOwnToken() {
      // given
      scenario.givenActive("member", false);
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(MEMBER, "member", "work"));
      // then
      assertFailure(failure, "forbidden");
    }

    @Test
    @DisplayName("비활성 구성원의 본인 폐기는 forbidden이다")
    void inactiveMemberCannotRevokeOwnToken() {
      // given
      var issued = scenario.givenMemberToken();
      scenario.givenActive("member", false);
      // when
      var failure = catchThrowable(() -> scenario.port().revokeToken(MEMBER, issued.tokenId()));
      // then
      assertFailure(failure, "forbidden");
    }

    @Test
    @DisplayName("비활성 구성원의 본인 조회는 forbidden이다")
    void inactiveMemberCannotListOwnTokens() {
      // given
      scenario.givenActive("member", false);
      // when
      var failure =
          catchThrowable(() -> scenario.port().tokens(MEMBER, "member", new PageRequest()));
      // then
      assertFailure(failure, "forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "missing"})
    @DisplayName("미등록 Principal의 본인 발급은 forbidden이다")
    void unknownActorCannotIssueToken(String actor) {
      // when
      var failure =
          catchThrowable(() -> scenario.port().issueToken(new Principal(actor), actor, "work"));
      // then
      assertFailure(failure, "forbidden");
    }

    @Test
    @DisplayName("비활성 대상에게 발급하면 conflict다")
    void inactiveTargetCannotReceiveToken() {
      // given
      scenario.givenActive("member", false);
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(ADMIN, "member", "work"));
      // then
      assertFailure(failure, "conflict");
    }

    @Test
    @DisplayName("없는 발급 대상은 not_found다")
    void unknownIssueTargetIsNotFound() {
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(ADMIN, "missing", "work"));
      // then
      assertFailure(failure, "not_found");
    }

    @Test
    @DisplayName("없는 폐기 대상은 not_found다")
    void unknownRevocationTargetIsNotFound() {
      // when
      var failure =
          catchThrowable(
              () ->
                  scenario
                      .port()
                      .revokeToken(
                          MEMBER, UUID.fromString("00000000-0000-0000-0000-000000000001")));
      // then
      assertFailure(failure, "not_found");
    }

    @Test
    @DisplayName("없는 조회 대상은 not_found다")
    void unknownListingTargetIsNotFound() {
      // when
      var failure =
          catchThrowable(() -> scenario.port().tokens(ADMIN, "missing", new PageRequest()));
      // then
      assertFailure(failure, "not_found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "a\nb", "a\tb", "a\u007fb", "a\u0085b", "\nwork", "work\n"})
    @DisplayName("빈 label이나 제어 문자는 invalid_request다")
    void invalidLabelIsRejected(String label) {
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(MEMBER, "member", label));
      // then
      assertFailure(failure, "invalid_request");
      assertThat(scenario.audit("token.issue")).isEmpty();
    }

    @Test
    @DisplayName("100자 label은 발급할 수 있다")
    void hundredCharacterLabelIsAccepted() {
      // when
      scenario.port().issueToken(MEMBER, "member", "a".repeat(100));
      // then
      assertThat(scenario.metadata(MEMBER, "member"))
          .extracting(TokenMetadata::label)
          .containsExactly("a".repeat(100));
    }

    @Test
    @DisplayName("101자 label은 invalid_request다")
    void oversizedLabelIsRejected() {
      // when
      var failure =
          catchThrowable(() -> scenario.port().issueToken(MEMBER, "member", "a".repeat(101)));
      // then
      assertFailure(failure, "invalid_request");
    }

    @Test
    @DisplayName("잘못된 토큰 커서는 invalid_request다")
    void invalidTokenCursorIsInvalidRequest() {
      // when
      var failure =
          catchThrowable(
              () ->
                  scenario
                      .port()
                      .tokens(MEMBER, "member", new PageRequest(1, Optional.of("invalid"))));
      // then
      assertFailure(failure, "invalid_request");
    }
  }

  @Nested
  @DisplayName("토큰 변경의 감사를 기록할 때")
  class Audit {
    @Test
    @DisplayName("발급 감사는 비밀 없이 요청자와 tokenId를 기록한다")
    void issueIsAuditedWithoutSecret() {
      // when
      var issued = scenario.port().issueToken(MEMBER, "member", "work");
      // then
      assertThat(scenario.audit("token.issue"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::detail)
          .containsExactly("member", "token.issue", "token", issued.tokenId().toString(), Map.of());
    }

    @Test
    @DisplayName("폐기 감사는 비밀 없이 요청자와 tokenId를 기록한다")
    void revocationIsAuditedWithoutSecret() {
      // given
      var issued = scenario.givenMemberToken();
      // when
      scenario.port().revokeToken(ADMIN, issued.tokenId());
      // then
      assertThat(scenario.audit("token.revoke"))
          .singleElement()
          .extracting(
              AuditEvent::actor,
              AuditEvent::action,
              AuditEvent::targetType,
              AuditEvent::targetId,
              AuditEvent::detail)
          .containsExactly("admin", "token.revoke", "token", issued.tokenId().toString(), Map.of());
    }

    @Test
    @DisplayName("발급 감사 실패는 토큰을 반환하지 않고, 저장도 롤백한다")
    void auditFailureDoesNotReturnIssuedToken() {
      // given
      scenario.failAudit("token.issue");
      // when
      var failure = catchThrowable(() -> scenario.port().issueToken(MEMBER, "member", "work"));
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Administration storage failed")
          .hasRootCauseMessage("injected token.issue audit failure");
      assertThat(
              List.of(
                  scenario.metadata(ADMIN, "member"),
                  scenario.observedAuthentications(),
                  scenario.audit("token.issue")))
          .containsExactly(List.of(), List.of(Optional.empty()), List.of());
    }

    @Test
    @DisplayName("폐기 감사 실패는 메타데이터와 인증을 보존한다")
    void revocationRollsBackWhenAuditFails() {
      // given
      var issued = scenario.givenMemberToken();
      scenario.failAudit("token.revoke");
      // when
      var failure = catchThrowable(() -> scenario.port().revokeToken(MEMBER, issued.tokenId()));
      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Administration storage failed")
          .hasRootCauseMessage("injected token.revoke audit failure");
      assertThat(
              List.of(
                  scenario.metadata(ADMIN, "member").getFirst().revokedAt(),
                  scenario.authenticates(issued.value()),
                  scenario.audit("token.revoke")))
          .containsExactly(Optional.empty(), Optional.of(new Principal("member")), List.of());
    }
  }

  @ParameterizedTest
  @CsvSource({"ISSUE_FIRST, success", "DEACTIVATE_FIRST, conflict"})
  @DisplayName("발급과 비활성화를 직렬화하고, 인증 가능한 토큰을 남기지 않는다")
  void issueRacingWithDeactivationLeavesNoUsableToken(Scenario.Order order, String outcome)
      throws Exception {
    // given
    scenario.givenMemberToken();
    try (var race = scenario.holdFirstMutation(order)) {
      // when
      race.requestSecondMutation();
      // then
      assertThatThrownBy(race::awaitWhileFirstPending).isInstanceOf(TimeoutException.class);
      assertThat(race.finish()).isEqualTo(outcome);
      assertThat(scenario.observedAuthentications()).isNotEmpty().allMatch(Optional::isEmpty);
    }
  }

  private void assertFailure(Throwable failure, String code) {
    assertThat(failure).isInstanceOf(AdminPort.Failure.class);
    assertThat(((AdminPort.Failure) failure).code()).isEqualTo(code);
  }

  /** 토큰 저장·인증 배선과 action별 장애, 경합 gate를 소유한다. */
  public abstract static class Scenario {
    private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
    protected static final DomainPolicy POLICY = new DomainPolicy(Set.of("example.com"), Map.of());
    private final AdminStore store;
    private final LockFailureStore lockFailures;
    private final UserTokens authentication;
    private final Set<TokenHash> observedHashes = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Gate> nextGate = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private String failedAction = "";

    protected Scenario(AdminStore store, UserTokens authentication) {
      this.store = store;
      lockFailures = new LockFailureStore(store);
      this.authentication = authentication;
    }

    public abstract AdminPort port();

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

    public final void givenTwoMemberTokens() {
      givenMemberToken();
      var home = port().issueToken(MEMBER, "member", "home");
      if (authenticates(home.value()).isEmpty())
        throw new IllegalStateException("given 준비 실패: 두 번째 토큰 없음");
    }

    public final Optional<Principal> authenticates(String value) {
      return authentication.authenticate(TokenHash.of(value));
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

    public final void failNextAdministrationLock() {
      lockFailures.failNextAdministrationLock();
    }

    protected final Clock tokenClock() {
      return clock;
    }

    protected final AdminStore tokenStore() {
      return new ObservingStore();
    }

    public final void advanceClock() {
      clock.advance();
    }

    public final IssuedToken givenMemberToken() {
      var issued = port().issueToken(MEMBER, "member", "work");
      if (authenticates(issued.value()).isEmpty())
        throw new IllegalStateException("given 준비 실패: 발급 토큰 없음");
      return issued;
    }

    public final IssuedToken givenAdminToken() {
      var issued = port().issueToken(ADMIN, "admin", "work");
      if (authenticates(issued.value()).isEmpty())
        throw new IllegalStateException("given 준비 실패: 관리자 토큰 없음");
      return issued;
    }

    public final IssuedToken givenRevokedMemberToken() {
      var issued = givenMemberToken();
      port().revokeToken(MEMBER, issued.tokenId());
      if (authenticates(issued.value()).isPresent())
        throw new IllegalStateException("given 준비 실패: 폐기되지 않음");
      return issued;
    }

    public final IssuedToken givenMemberTokenOnSecondPage() {
      var values = new HashMap<UUID, IssuedToken>();
      for (int i = 0; i < 51; i++) {
        var issued = port().issueToken(MEMBER, "member", "page-" + i);
        values.put(issued.tokenId(), issued);
      }
      var first = port().tokens(MEMBER, "member", new PageRequest());
      if (first.nextCursor().isEmpty()) throw new IllegalStateException("given 준비 실패: 둘째 페이지 없음");
      var second = port().tokens(MEMBER, "member", new PageRequest(50, first.nextCursor()));
      var issued = values.get(second.items().getFirst().tokenId());
      if (authenticates(issued.value()).isEmpty())
        throw new IllegalStateException("given 준비 실패: 둘째 페이지 토큰 없음");
      return issued;
    }

    public final IssuedToken givenRevokedMemberTokenOnSecondPage() {
      var issued = givenMemberTokenOnSecondPage();
      port().revokeToken(MEMBER, issued.tokenId());
      if (authenticates(issued.value()).isPresent())
        throw new IllegalStateException("given 준비 실패: 둘째 페이지 토큰 미폐기");
      return issued;
    }

    public final TokenMetadata metadataOf(UUID tokenId) {
      return port()
          .tokens(MEMBER, "member", new PageRequest(200, Optional.empty()))
          .items()
          .stream()
          .filter(token -> token.tokenId().equals(tokenId))
          .findFirst()
          .orElseThrow();
    }

    public final List<TokenMetadata> metadata(Principal actor, String userId) {
      return port().tokens(actor, userId, new PageRequest()).items();
    }

    public final boolean storedHashMatches(IssuedToken issued) {
      return store.transact(
          session ->
              session
                  .token(issued.tokenId())
                  .orElseThrow()
                  .hash()
                  .equals(TokenHash.of(issued.value())));
    }

    public final List<Optional<Principal>> observedAuthentications() {
      return observedHashes.stream().map(authentication::authenticate).toList();
    }

    public final IssuanceRace holdFirstMutation(Order order) {
      return new IssuanceRace(order);
    }

    private final class ObservingStore implements AdminStore {
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
        if (method.getName().equals("tokenByHash")) observedHashes.add((TokenHash) arguments[0]);
        try {
          Object result = method.invoke(session, arguments);
          if (method.getName().equals("lockAdministration")) {
            var gate = nextGate.getAndSet(null);
            if (gate != null) gate.hold();
          }
          return result;
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }
    }

    public enum Order {
      ISSUE_FIRST,
      DEACTIVATE_FIRST
    }

    public final class IssuanceRace implements AutoCloseable {
      private static final Duration WAIT = Duration.ofSeconds(5);
      private static final Duration PENDING = Duration.ofMillis(200);
      private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
      private final Gate gate = new Gate();
      private final CountDownLatch requested = new CountDownLatch(1);
      private final Order order;
      private final Future<String> first;
      private Future<String> second;

      private IssuanceRace(Order order) {
        this.order = order;
        nextGate.set(gate);
        first = workers.submit(() -> mutate(order == Order.ISSUE_FIRST));
        gate.awaitHeld();
      }

      public void requestSecondMutation() {
        second =
            workers.submit(
                () -> {
                  requested.countDown();
                  return mutate(order != Order.ISSUE_FIRST);
                });
        Gate.await(requested);
      }

      public void awaitWhileFirstPending() throws Exception {
        second.get(PENDING.toMillis(), TimeUnit.MILLISECONDS);
      }

      public String finish() throws Exception {
        gate.release();
        String firstResult = first.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        String secondResult = second.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        return order == Order.ISSUE_FIRST ? firstResult : secondResult;
      }

      private String mutate(boolean issue) {
        try {
          if (issue) port().issueToken(ADMIN, "member", "race");
          else port().deactivateUser(ADMIN, "member");
          return "success";
        } catch (AdminPort.Failure failure) {
          return failure.code();
        }
      }

      @Override
      public void close() {
        gate.release();
        workers.close();
      }
    }

    private static final class Gate {
      private static final Duration WAIT = Duration.ofSeconds(5);
      private final CountDownLatch held = new CountDownLatch(1);
      private final CountDownLatch released = new CountDownLatch(1);

      void hold() {
        held.countDown();
        await(released);
      }

      void awaitHeld() {
        await(held);
      }

      void release() {
        released.countDown();
      }

      static void await(CountDownLatch latch) {
        try {
          if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Token race timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
    }

    private static final class MutableClock extends Clock {
      private volatile Instant now = Instant.parse("2026-11-08T00:00:00Z");

      void advance() {
        now = Instant.parse("2026-11-08T00:01:00Z");
      }

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
      }

      @Override
      public Instant instant() {
        return now;
      }
    }
  }
}
