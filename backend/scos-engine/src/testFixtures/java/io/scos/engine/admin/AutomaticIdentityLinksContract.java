package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.engine.admin.IdentityReconciler.ReconcileReport;
import io.scos.engine.internal.admin.AutomaticIdentityLinks;
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
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.support.LogCapture;
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
import org.junit.jupiter.api.parallel.Isolated;

/** 메모리와 PG에서 실제 reconciler·관리·읽기·directory 병합의 같은 계약을 실행한다. */
@Isolated
@DisplayName("자동 연결은 최신 조직 신원과 원자적 system 감사를 따른다(§7.3)")
public abstract class AutomaticIdentityLinksContract {
  protected static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  private static final Principal ADMIN = new Principal("admin");
  protected Scenario scenario;

  protected abstract Scenario newScenario();

  @BeforeEach
  void setUp() {
    scenario = newScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("alice", "alice@example.com", true, Role.MEMBER);
    scenario.givenUser("bob", "bob@example.com", true, Role.MEMBER);
    scenario.registerSource(new SourceAccount(SLACK, "slack"));
    scenario.observe(SLACK, scenario.identity().build());
  }

  @Test
  @DisplayName("관리 잠금 획득이 실패하면 자동 연결과 감사가 남지 않는다")
  void administrationLockFailurePreventsReconciliation() {
    scenario.failNextAdministrationLock();
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 1));
    assertThat(scenario.links()).isEmpty();
    assertThat(scenario.audit("identity.link")).isEmpty();
  }

  @Test
  @DisplayName("검증된 조직 신원은 활성 사용자에게 auto로 연결한다")
  void verifiedOrganizationIdentityLinksActiveMatchingUser() {
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(1, 0, 0, 0));
    assertThat(scenario.links())
        .containsExactly(
            new IdentityLink(
                SLACK,
                "U1",
                "alice",
                LinkMethod.AUTO,
                Instant.parse("2026-11-08T00:00:00Z"),
                "system",
                Set.of()));
    assertThat(scenario.keys("alice")).containsExactly("user:slack-a:U1");
  }

  @Test
  @DisplayName("Slack은 인스턴스 도메인 지정 없이 조직 도메인으로 연결한다")
  void slackLinksWithoutInstanceDomainOverride() {
    // when
    scenario.reconcile();
    // then
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("Tiro는 허용 도메인이면 검증 플래그 없이 연결한다")
  void tiroAllowedDomainDoesNotRequireVerifiedFlag() {
    // given
    scenario.registerSource(new SourceAccount(SLACK, "tiro"));
    scenario.observe(SLACK, scenario.identity().unverified().build());
    // when
    scenario.reconcile();
    // then
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("검증되지 않은 신원은 연결하지 않는다")
  void unverifiedIdentityDoesNotLink() {
    // given
    scenario.observe(SLACK, scenario.identity().unverified().build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("조직 밖 신원은 일치하는 사용자가 있어도 연결하지 않는다")
  void outsideOrganizationDoesNotLink() {
    // given
    scenario.givenUser("outside", "outside@other.example", true, Role.MEMBER);
    scenario.observe(SLACK, scenario.identity().email("outside@other.example").build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("게스트 신원은 연결하지 않는다")
  void guestIdentityDoesNotLink() {
    // given
    scenario.observe(SLACK, scenario.identity().guest().build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("탈퇴한 신원은 연결하지 않는다")
  void deletedIdentityDoesNotLink() {
    // given
    scenario.observe(SLACK, scenario.identity().deleted().build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("일치하는 사용자가 없는 신원은 연결하지 않는다")
  void unmatchedEmailDoesNotLink() {
    // given
    scenario.observe(SLACK, scenario.identity().email("other@example.com").build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("비활성 사용자는 일치하는 신원이 있어도 연결하지 않는다")
  void inactiveUserDoesNotLink() {
    // given
    scenario.deactivateAlice();
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("공백과 대소문자 차이를 정규화하면 한 번만 연결한다")
  void caseInsensitiveEmailLinksOnce() {
    // given
    scenario.observe(SLACK, scenario.identity().email("  ALICE@EXAMPLE.COM  ").build());
    scenario.givenReconciled();
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
    assertThat(scenario.audit("identity.link")).hasSize(1);
  }

  @Test
  @DisplayName("같은 이메일의 여러 원천 신원은 각각 같은 사용자에게 연결한다")
  void multipleIdentitiesCanLinkSameUser() {
    // given
    scenario.observe(SLACK, scenario.identity().id("U2").build());
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(2, 0, 0, 0));
    assertThat(scenario.keys("alice"))
        .containsExactlyInAnyOrder("user:slack-a:U1", "user:slack-a:U2");
  }

  @Nested
  @DisplayName("alice에게 auto로 연결된 상태에서")
  class LinkedAutomatically {
    @BeforeEach
    void autoLink() {
      scenario.givenLink("alice", LinkMethod.AUTO);
    }

    @Test
    @DisplayName("이메일이 일치하지 않게 바뀌면 auto 연결을 해제한다")
    void changedEmailUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().email("other@example.com").build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("검증 상태가 사라지면 auto 연결을 해제한다")
    void unverifiedEmailUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().unverified().build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("이메일 관측이 사라지면 auto 연결을 해제한다")
    void emptyEmailUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().withoutEmail().build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("게스트로 바뀌면 auto 연결을 해제한다")
    void guestChangeUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().guest().build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 신호를 받으면 auto 연결을 해제한다")
    void deletedChangeUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().deleted().build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("조직 도메인이 아니면 auto 연결을 해제한다")
    void disallowedDomainUnlinksAuto() {
      // given
      scenario.observe(SLACK, scenario.identity().email("outside@other.example").build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.keys("alice")).isEmpty();
    }

    @Test
    @DisplayName("사용자가 비활성화되면 auto 연결을 해제한다")
    void inactiveUserUnlinksAuto() {
      // given
      scenario.deactivateAlice();
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 1, 0, 0));
      assertThat(scenario.links()).isEmpty();
    }

    @Test
    @DisplayName("부분 목록에 없던 신원의 기존 auto 연결은 유지한다")
    void missingPartialIdentityKeepsAutoLink() {
      // given
      scenario.givenMissingPartialIdentity();
      // when
      scenario.reconcile();
      // then
      assertThat(scenario.links())
          .extracting(IdentityLink::sourceUserId)
          .containsExactly("U1", "U2");
      assertThat(scenario.audit("identity.unlink")).isEmpty();
    }

    @Test
    @DisplayName("새 이메일이 bob과 일치하면 alice 키를 회수하고 bob에게 연결한다")
    void changedEmailMovesAutoLinkToMatchingUser() {
      // given
      scenario.observe(SLACK, scenario.identity().email("bob@example.com").build());
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(1, 1, 0, 0));
      assertThat(scenario.keys("alice")).isEmpty();
      assertThat(scenario.keys("bob")).containsExactly("user:slack-a:U1");
    }

    @Test
    @DisplayName("자동 해제 감사가 실패하면 기존 연결과 감사를 보존한다")
    void auditFailureRollsBackAutomaticUnlink() {
      // given
      scenario.observe(SLACK, scenario.identity().unverified().build());
      scenario.failAudit("identity.unlink");
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 1));
      assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
      assertThat(scenario.audit("identity.unlink")).isEmpty();
    }

    @Test
    @DisplayName("일치 신원의 admin unlink 뒤에는 system이 새로 한 번 연결한다")
    void adminUnlinkOfEligibleAutoLinkIsRecreated() {
      // given
      scenario.port().unlinkIdentity(ADMIN, SLACK, "U1");
      // when
      scenario.reconcile();
      // then
      assertThat(scenario.links())
          .singleElement()
          .extracting(IdentityLink::userId, IdentityLink::method, IdentityLink::linkedBy)
          .containsExactly("alice", LinkMethod.AUTO, "system");
      assertThat(scenario.audit("identity.link")).hasSize(1);
    }

    @Test
    @DisplayName("비활성화 후 unlink된 신원은 재연결되지 않고 옛 토큰도 거부된다")
    void deactivatedUserIsNotRelinkedAfterAdminUnlink() {
      // given
      scenario.givenAliceToken();
      scenario.deactivateAlice();
      scenario.port().unlinkIdentity(ADMIN, SLACK, "U1");
      // when
      scenario.reconcile();
      // then
      assertThat(scenario.links()).isEmpty();
      assertThat(scenario.authenticatedAlice()).isEmpty();
    }

    @Test
    @DisplayName("비활성화 뒤 전체 정비를 해도 키와 옛 토큰은 회수된 채다")
    void deactivationKeepsAccessRevokedAcrossDailyReconciliation() {
      // given
      scenario.givenAliceToken();
      scenario.deactivateAlice();
      // when
      scenario.reconcileAll();
      // then
      assertThat(scenario.keys("alice")).isEmpty();
      assertThat(scenario.authenticatedAlice()).isEmpty();
    }

    @Test
    @DisplayName("병합 커밋을 기다린 뒤에는 최신 이메일의 bob에게만 연결한다")
    void reconcileWaitsForDirectoryMergeAndUsesNewEmail() throws Exception {
      // given
      try (var race = scenario.holdDirectoryMerge()) {
        // when
        race.requestReconciliation();
        // then
        assertThatThrownBy(race::awaitWhilePending).isInstanceOf(TimeoutException.class);
        race.commitAndAwait();
        assertThat(scenario.keys("alice")).isEmpty();
        assertThat(scenario.keys("bob")).containsExactly("user:slack-a:U1");
      }
    }
  }

  @Nested
  @DisplayName("수동 연결이 있는 상태에서")
  class LinkedManually {
    @BeforeEach
    void manualLink() {
      scenario.givenLink("bob", LinkMethod.ADMIN);
    }

    @Test
    @DisplayName("적격 신원을 재조정해도 수동 대상은 덮어쓰지 않는다")
    void autoNeverOverwritesAdminLink() {
      // when
      var result = scenario.reconcile();
      // then
      assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
      assertThat(scenario.links())
          .singleElement()
          .extracting(IdentityLink::userId, IdentityLink::method)
          .containsExactly("bob", LinkMethod.ADMIN);
    }

    @Test
    @DisplayName("이메일이 바뀌어도 수동 연결은 유지한다")
    void adminLinkSurvivesEmailChange() {
      // given
      scenario.observe(SLACK, scenario.identity().email("other@example.com").build());
      // when
      scenario.reconcile();
      // then
      assertThat(scenario.links())
          .singleElement()
          .extracting(IdentityLink::userId, IdentityLink::method)
          .containsExactly("bob", LinkMethod.ADMIN);
    }

    @Test
    @DisplayName("게스트가 되면 수동 연결은 유지하고 읽기 키는 회수한다")
    void adminLinkSurvivesGuestChange() {
      // given
      scenario.observe(SLACK, scenario.identity().guest().build());
      // when
      scenario.reconcile();
      // then
      assertThat(scenario.links())
          .singleElement()
          .extracting(IdentityLink::method, IdentityLink::warnings)
          .containsExactly(LinkMethod.ADMIN, Set.of("guest"));
      assertThat(scenario.keys("bob")).isEmpty();
    }
  }

  @Test
  @DisplayName("수동 연결이 먼저 커밋되면 대기 중인 자동 연결은 이를 보존한다")
  void manualLinkWinsReconciliationRace() throws Exception {
    // given
    try (var race = scenario.holdManualLink()) {
      // when
      race.requestReconciliation();
      // then
      assertThatThrownBy(race::awaitWhilePending).isInstanceOf(TimeoutException.class);
      race.commitAndAwait();
      assertThat(scenario.links())
          .singleElement()
          .extracting(IdentityLink::userId, IdentityLink::method)
          .containsExactly("bob", LinkMethod.ADMIN);
      assertThat(scenario.audit("identity.link"))
          .singleElement()
          .extracting(AuditEvent::actor)
          .isEqualTo("admin");
    }
  }

  @Test
  @DisplayName("자동 연결 감사가 실패하면 보고·연결·키·감사는 함께 롤백된다")
  void auditFailureRollsBackAutomaticLink() {
    // given
    scenario.failAudit("identity.link");
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 1));
    assertThat(List.of(scenario.links(), scenario.keys("alice"), scenario.audit("identity.link")))
        .containsExactly(List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("대상 변경의 연결 감사가 실패하면 해제도 롤백한다")
  void auditFailureRollsBackRetargeting() {
    // given
    scenario.givenLink("alice", LinkMethod.AUTO);
    scenario.observe(SLACK, scenario.identity().email("bob@example.com").build());
    scenario.failAudit("identity.link");
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 1));
    assertThat(scenario.links())
        .singleElement()
        .extracting(IdentityLink::userId)
        .isEqualTo("alice");
    assertThat(scenario.audit("identity.unlink")).isEmpty();
  }

  @Test
  @DisplayName("첫 소스 처리 중 취소되면 커밋은 보존하고 다음 소스를 시작하지 않는다")
  void cancellationBetweenSourcesKeepsCommittedSource() {
    // given
    scenario.givenSecondSource();
    // when
    var result = scenario.reconcileFirstSourceThenCancel();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(1, 0, 0, 0));
    assertThat(scenario.links())
        .extracting(link -> link.source().value())
        .containsExactly("slack-a");
  }

  @Test
  @DisplayName("DB 정렬과 다른 Unicode 소스도 id순 첫 커밋만 보존한다")
  void unicodeSourceOrderControlsFirstCommittedSource() {
    scenario.givenUnicodeSources();
    // when
    var result = scenario.reconcileFirstSourceThenCancel();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(1, 0, 1, 0));
    assertThat(scenario.links())
        .extracting(link -> link.source().value())
        .containsExactly("slack-\uD800\uDC00");
  }

  @Test
  @DisplayName("자동 연결과 해제는 system 주체와 auto 방법으로 감사한다")
  void automaticChangesUseSystemActor() {
    // given
    scenario.givenReconciled();
    scenario.observe(SLACK, scenario.identity().email("bob@example.com").build());
    // when
    scenario.reconcile();
    // then
    assertThat(scenario.audit("identity.link"))
        .extracting(
            AuditEvent::actor, AuditEvent::targetType, AuditEvent::targetId, AuditEvent::detail)
        .containsExactlyInAnyOrder(
            tuple("system", "identity", "slack-a:U1", Map.of("userId", "alice", "method", "auto")),
            tuple("system", "identity", "slack-a:U1", Map.of("userId", "bob", "method", "auto")));
    assertThat(scenario.audit("identity.unlink"))
        .singleElement()
        .extracting(
            AuditEvent::actor, AuditEvent::targetType, AuditEvent::targetId, AuditEvent::detail)
        .containsExactly(
            "system", "identity", "slack-a:U1", Map.of("userId", "alice", "method", "auto"));
  }

  @Test
  @DisplayName("두 번째 재조정은 변화와 감사를 반복하지 않는다")
  void secondReconciliationDoesNotRepeatAudit() {
    // given
    scenario.givenReconciled();
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 1, 0));
    assertThat(scenario.audit("identity.link")).hasSize(1);
    assertThat(scenario.audit("identity.unlink")).isEmpty();
  }

  @Test
  @DisplayName("일일 정비 실패는 비밀 없이 실패 소스·예외 종류·참조를 남긴다")
  void dailyFailureIdentifiesSourceAndErrorWithoutPrivateMessage() {
    scenario.failSource(SLACK);
    try (var logs = LogCapture.of(AutomaticIdentityLinks.class)) {
      // when
      scenario.reconcileAll();
      // then
      var events = logs.events("admin.identity_reconcile.source_failed");
      assertThat(events)
          .singleElement()
          .extracting(
              event -> event.path("sourceInstanceId").asString(),
              event -> event.path("detail").path("errorType").asString(),
              event -> event.has("error"))
          .containsExactly("slack-a", "IllegalStateException", false);
      assertThat(events.getFirst().path("detail").path("ref").asString()).matches("[a-f0-9]{12}");
      assertThat(events.toString())
          .doesNotContain("alice@example.com", "identity.link", "injected automatic audit failure");
    }
  }

  @Test
  @DisplayName("한 소스 감사 실패는 롤백하고 다음 소스 연결을 계속한다")
  void failedSourceDoesNotStopOtherSources() {
    // given
    scenario.givenSecondSource();
    scenario.failSource(SLACK);
    // when
    var result = scenario.reconcileAll();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(1, 0, 0, 1));
    assertThat(scenario.links())
        .extracting(link -> link.source().value())
        .containsExactly("slack-b");
  }

  @Test
  @DisplayName("취소된 전체 정비는 연결과 감사를 만들지 않는다")
  void cancelledReconciliationDoesNotMutate() {
    // given
    var cancel = new CancellationToken();
    cancel.cancel();
    // when
    var result = scenario.reconciler().reconcileAll(cancel);
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("미등록 소스의 재조정은 변화 없이 끝난다")
  void missingSourceDoesNotMutate() {
    // when
    var result = scenario.reconciler().reconcile(new SourceInstanceId("missing-a"));
    // then
    assertThat(result).isEqualTo(new ReconcileReport(0, 0, 0, 0));
    assertThat(scenario.audit("identity.link")).isEmpty();
  }

  /** 실제 포트 조립은 구현 훅에 두고 장애·경합·공개 관찰 어휘를 공유한다. */
  public abstract static class Scenario {
    protected static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
    protected static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    protected static final DomainPolicy POLICY = new DomainPolicy(Set.of("example.com"), Map.of());
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Duration PENDING = Duration.ofMillis(200);
    private final AdminStore store;
    private final LockFailureStore lockFailures;
    private final IdentityStore identities;
    private final UserTokens tokens;
    private String failedAction = "";
    private String failedSource = "";
    private Runnable cancelAfterAutomaticLink = () -> {};
    private volatile Runnable beforeTransaction = () -> {};
    private volatile Runnable afterManualAudit = () -> {};

    protected Scenario(AdminStore store, IdentityStore identities, UserTokens tokens) {
      this.store = store;
      lockFailures = new LockFailureStore(store);
      this.identities = identities;
      this.tokens = tokens;
    }

    public abstract AdminPort port();

    public abstract IdentityReconciler reconciler();

    public abstract void registerSource(SourceAccount source);

    public abstract void observe(SourceInstanceId source, SourceIdentityRecord identity);

    protected abstract void mergeDirectory(SourceIdentityRecord identity, Runnable beforeCommit);

    public IdentityBuilder identity() {
      return new IdentityBuilder();
    }

    public void givenUser(String id, String email, boolean active, Role role) {
      store.transact(
          session -> {
            session.insertUser(new UserAccount(id, email, active, role, AT));
            return true;
          });
    }

    public void givenLink(String user, LinkMethod method) {
      store.transact(
          session ->
              session.putLink(
                  new IdentityLink(
                      SLACK,
                      "U1",
                      user,
                      method,
                      Instant.parse("2026-11-01T00:00:00Z"),
                      "fixture",
                      Set.of())));
      if (links().size() != 1) throw new IllegalStateException("given 준비 실패: 연결 없음");
    }

    public void givenReconciled() {
      if (!reconcile().equals(new ReconcileReport(1, 0, 0, 0)))
        throw new IllegalStateException("given 준비 실패: 자동 연결 없음");
    }

    public void givenMissingPartialIdentity() {
      observe(SLACK, identity().id("U2").email("bob@example.com").build());
      store.transact(
          session ->
              session.putLink(
                  new IdentityLink(SLACK, "U2", "bob", LinkMethod.AUTO, AT, "fixture", Set.of())));
      // 실제 PARTIAL 병합에 U1만 포함한다. 이전에 관측한 U2의 연결은 유지한다.
      mergeDirectory(identity().build(), () -> {});
    }

    public void givenSecondSource() {
      var other = new SourceInstanceId("slack-b");
      registerSource(new SourceAccount(other, "slack"));
      observe(other, identity().build());
    }

    public void givenUnicodeSources() {
      observe(SLACK, identity().guest().build());
      for (String value : List.of("slack-\uE000", "slack-\uD800\uDC00")) {
        var source = new SourceInstanceId(value);
        registerSource(new SourceAccount(source, "slack"));
        observe(source, identity().build());
      }
    }

    public void deactivateAlice() {
      port().deactivateUser(ADMIN, "alice");
    }

    public void givenAliceToken() {
      tokens.store(TokenHash.of("synthetic-alice-token"), "alice", "work", AT);
      if (authenticatedAlice().isEmpty()) throw new IllegalStateException("given 준비 실패: 토큰 없음");
    }

    public Optional<Principal> authenticatedAlice() {
      return tokens.authenticate(TokenHash.of("synthetic-alice-token"));
    }

    public ReconcileReport reconcile() {
      return reconciler().reconcile(SLACK);
    }

    public ReconcileReport reconcileFirstSourceThenCancel() {
      var cancel = new CancellationToken();
      cancelAfterAutomaticLink = cancel::cancel;
      return reconciler().reconcileAll(cancel);
    }

    public ReconcileReport reconcileAll() {
      return reconciler().reconcileAll(CancellationToken.none());
    }

    public List<IdentityLink> links() {
      return port().links(ADMIN, Optional.empty(), new PageRequest()).items();
    }

    public List<String> keys(String user) {
      return identities.accessKeysOf(user, POLICY).orElse(Set.of()).stream()
          .map(Object::toString)
          .toList();
    }

    public List<AuditEvent> audit(String action) {
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

    public void failAudit(String action) {
      failedAction = action;
    }

    public void failNextAdministrationLock() {
      lockFailures.failNextAdministrationLock();
    }

    public void failSource(SourceInstanceId source) {
      failedSource = source.value();
    }

    protected AdminStore serviceStore() {
      return new FaultingStore();
    }

    public ReconciliationRace holdDirectoryMerge() {
      return new ReconciliationRace(true);
    }

    public ReconciliationRace holdManualLink() {
      return new ReconciliationRace(false);
    }

    private final class FaultingStore implements AdminStore {
      @Override
      public <T> T transact(Function<Session, T> work) {
        beforeTransaction.run();
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
        if (method.getName().equals("appendAudit") && arguments[0] instanceof NewAuditEvent event) {
          if (event.action().equals(failedAction)
              || event.targetId().startsWith(failedSource + ":"))
            throw new IllegalStateException("injected automatic audit failure");
          Object result = call(session, method, arguments);
          if (event.actor().equals("system") && event.action().equals("identity.link"))
            cancelAfterAutomaticLink.run();
          if (event.actor().equals("admin") && event.action().equals("identity.link"))
            afterManualAudit.run();
          return result;
        }
        return call(session, method, arguments);
      }

      private Object call(Session session, Method method, Object[] arguments) throws Throwable {
        try {
          return method.invoke(session, arguments);
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }
    }

    public final class ReconciliationRace implements AutoCloseable {
      private final ExecutorService workers = Executors.newFixedThreadPool(2);
      private final CountDownLatch held = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final CountDownLatch requested = new CountDownLatch(1);
      private final Future<?> first;
      private Future<ReconcileReport> second;

      private ReconciliationRace(boolean directory) {
        if (directory)
          first =
              workers.submit(
                  () ->
                      mergeDirectory(
                          identity().email("bob@example.com").observedLater().build(), this::hold));
        else {
          afterManualAudit = this::hold;
          first = workers.submit(() -> port().linkIdentity(ADMIN, "bob", SLACK, "U1"));
        }
        try {
          await(held);
        } catch (RuntimeException | Error failure) {
          close();
          throw failure;
        }
      }

      private void hold() {
        held.countDown();
        await(release);
      }

      public void requestReconciliation() {
        beforeTransaction = requested::countDown;
        second = workers.submit(Scenario.this::reconcile);
        await(requested);
      }

      public void awaitWhilePending() throws Exception {
        second.get(PENDING.toMillis(), TimeUnit.MILLISECONDS);
      }

      public ReconcileReport commitAndAwait() throws Exception {
        release.countDown();
        first.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        return second.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      }

      @Override
      public void close() {
        release.countDown();
        workers.close();
        beforeTransaction = () -> {};
        afterManualAudit = () -> {};
      }
    }

    private static void await(CountDownLatch gate) {
      try {
        if (!gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
          throw new IllegalStateException("Reconciliation gate timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
  }

  /** 기본값은 원천에서 관측한 검증된 조직 정회원이다. */
  public static final class IdentityBuilder {
    private String id = "U1";
    private Optional<String> email = Optional.of("alice@example.com");
    private Instant at = Instant.parse("2026-11-08T00:00:00Z");
    private boolean verified = true;
    private boolean guest;
    private boolean deleted;

    public IdentityBuilder observedLater() {
      at = Instant.parse("2026-11-08T00:00:01Z");
      return this;
    }

    public IdentityBuilder id(String value) {
      id = value;
      return this;
    }

    public IdentityBuilder email(String value) {
      email = Optional.of(value);
      return this;
    }

    public IdentityBuilder withoutEmail() {
      email = Optional.empty();
      return this;
    }

    public IdentityBuilder unverified() {
      verified = false;
      return this;
    }

    public IdentityBuilder guest() {
      guest = true;
      return this;
    }

    public IdentityBuilder deleted() {
      deleted = true;
      return this;
    }

    public SourceIdentityRecord build() {
      return new SourceIdentityRecord(id, email, verified, guest, deleted, at);
    }
  }
}
