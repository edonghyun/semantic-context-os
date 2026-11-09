package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.admin.AdminBootstrap.BootstrapResult;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminUsersContract.Scenario;
import io.scos.engine.admin.AutomaticIdentityLinksContract.IdentityBuilder;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.engine.admin.IdentityReconciler.ReconcileReport;
import io.scos.engine.admin.IssuedToken;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.TokenHash;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리 팩터리는 실제 포트와 현재 권한 정책을 조립한다(§7.1)")
class AdminConfigurationTest {
  private FactoryScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new FactoryScenario();
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenSecondAdmin();
  }

  @Test
  @DisplayName("공용 팩터리의 포트는 사용자를 생성하고, 감사를 게시한다")
  void factoryCreatesUsableAdminPort() {
    // when
    var created =
        scenario.port().createUser(new Principal("admin"), "alice@example.com", Role.MEMBER);
    // then
    assertThat(scenario.user(created.id()).email()).isEqualTo("alice@example.com");
    assertThat(scenario.audit("user.create"))
        .singleElement()
        .extracting(AuditEvent::actor, AuditEvent::targetId)
        .containsExactly("admin", created.id());
  }

  @Test
  @DisplayName("같은 포트에서 강등된 Principal의 생성 요청은 forbidden이다")
  void factoryPreservesCurrentAdminCheck() {
    // given
    scenario.givenDemotedAdmin();
    // when
    var failure =
        catchThrowable(
            () ->
                scenario
                    .port()
                    .createUser(new Principal("admin"), "alice@example.com", Role.MEMBER));
    // then
    assertThat(failure).isInstanceOf(AdminPort.Failure.class);
    assertThat(((AdminPort.Failure) failure).code()).isEqualTo("forbidden");
  }

  @Test
  @DisplayName("공용 팩터리의 reconciler는 같은 상태의 적격 신원을 연결한다")
  void factoryCreatesWorkingIdentityReconciler() {
    // given
    scenario.givenEligibleIdentity();
    // when
    var result = scenario.reconcile();
    // then
    assertThat(result).isEqualTo(new ReconcileReport(1, 0, 0, 0));
    assertThat(
            scenario
                .port()
                .links(new Principal("admin"), Optional.empty(), new PageRequest())
                .items())
        .singleElement()
        .extracting(IdentityLink::userId, IdentityLink::linkedBy)
        .containsExactly("alice", "system");
  }

  @Test
  @DisplayName("관리 팩터리의 포트는 연결한 원천 진단을 반환한다")
  void factoryConnectsAdministrationDiagnostics() {
    // when
    var result = scenario.port().sourceAccess(new Principal("admin"));
    // then
    assertThat(result)
        .singleElement()
        .extracting(
            value -> value.source().value(),
            SourceAccessView::kind,
            SourceAccessView::bodyResult,
            SourceAccessView::result)
        .containsExactly(
            "drive-a", "google-drive", Optional.of("succeeded"), Optional.of("incomplete"));
  }

  @Test
  @DisplayName("bootstrap 팩터리는 최초 관리자와 인증 가능한 토큰을 준비한다")
  void factoryCreatesWorkingBootstrap() {
    // given
    var bootstrap = new BootstrapFactoryScenario();
    // when
    var result = bootstrap.run();
    // then
    assertThat(bootstrap.email(result)).isEqualTo("first@example.com");
    assertThat(bootstrap.authenticates()).isTrue();
  }

  private static final class BootstrapFactoryScenario {
    private final MemoryAdminScenario storage = new MemoryAdminScenario();
    private IssuedToken delivered;

    BootstrapResult run() {
      return AdminConfiguration.newAdminBootstrap(storage.store(), Clock.systemUTC())
          .bootstrap("first@example.com", token -> delivered = token);
    }

    String email(BootstrapResult result) {
      return storage
          .store()
          .transact(session -> session.user(result.userId()).orElseThrow().email());
    }

    boolean authenticates() {
      return storage.authentication().authenticate(TokenHash.of(delivered.value())).isPresent();
    }
  }

  private static final class FactoryScenario extends Scenario {
    private final AdminPort port;
    private final MemoryAdminScenario storage;

    private FactoryScenario() {
      this(new MemoryAdminScenario());
    }

    private FactoryScenario(MemoryAdminScenario storage) {
      super(storage.store(), storage.authentication());
      this.storage = storage;
      port =
          AdminConfiguration.newAdminPort(
              serviceStore(),
              CLOCK,
              POLICY,
              new FixedAdminDiagnostics(List.of(diagnosticSource())));
    }

    private SourceAccessView diagnosticSource() {
      return new SourceAccessView(
          new SourceInstanceId("drive-a"),
          "google-drive",
          Optional.of("succeeded"),
          Optional.of("incomplete"),
          Optional.of(CLOCK.instant()),
          0,
          0,
          0,
          0,
          0,
          Optional.empty(),
          false,
          1,
          Optional.empty(),
          0,
          Set.of(),
          Optional.empty());
    }

    @Override
    public AdminPort port() {
      return port;
    }

    void givenEligibleIdentity() {
      givenUser("alice", "alice@example.com", true, Role.MEMBER);
      var source = new SourceInstanceId("slack-a");
      storage.registerSource(new SourceAccount(source, "slack"));
      storage.observeIdentity(source, new IdentityBuilder().build());
    }

    ReconcileReport reconcile() {
      return AdminConfiguration.newIdentityReconciler(serviceStore(), CLOCK, POLICY)
          .reconcile(new SourceInstanceId("slack-a"));
    }

    void givenDemotedAdmin() {
      port.issueToken(new Principal("second-admin"), "second-admin", "work");
      port.changeRole(new Principal("second-admin"), "admin", Role.MEMBER);
      if (user(new Principal("second-admin"), "admin").role() != Role.MEMBER)
        throw new IllegalStateException("given 준비 실패: admin이 강등되지 않음");
    }
  }
}
