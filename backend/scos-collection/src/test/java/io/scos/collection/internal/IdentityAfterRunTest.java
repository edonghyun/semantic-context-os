package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.AdminConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminStore;
import io.scos.store.memory.MemorySourceAccessDirectory;
import io.scos.store.memory.MemoryUserTokens;
import io.scos.support.LogCapture;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Isolated
@DisplayName("run 정리 후 확정된 신원으로 자동 연결한다")
public class IdentityAfterRunTest {
  protected Scenario scenario;

  protected Scenario newScenario() {
    return Scenario.memory();
  }

  @BeforeEach
  void prepare() {
    scenario = newScenario();
  }

  @AfterEach
  void cleanupTerminalFault() {
    scenario.cleanup.run();
  }

  @Test
  @DisplayName("콜백은 terminal run·확정 directory·점유 반환·heartbeat 정지 뒤 실행한다")
  protected void reconcilesAfterTerminalRunCommit() {
    scenario.collect();
    // then
    assertThat(scenario.atReconciliation())
        .containsExactly(new CompletionState(RunStatus.SUCCEEDED, true, false, 0));
    assertThat(scenario.links()).extracting(IdentityLink::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("본문 실패 후에도 guest로 바뀐 신원의 자동 연결을 회수한다")
  protected void bodyFailureStillReconcilesRestrictedIdentity() {
    scenario.givenAutoLink();
    scenario.guestWithFailedBody();
    var done = scenario.collect();
    // then
    assertThat(done.status()).isEqualTo(RunStatus.FAILED);
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("접근 병합 뒤 terminal 커밋이 실패해도 변경 신원의 자동 연결을 회수한다")
  protected void terminalCommitFailureStillReconcilesMergedIdentity() {
    scenario.givenAutoLink();
    scenario.changedEmailWithTerminalFailure();
    // when
    var failure = catchThrowable(scenario::collect);
    // then
    assertThat(failure)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("terminal commit failure");
    assertThat(scenario.atReconciliation())
        .containsExactly(new CompletionState(RunStatus.RUNNING, true, false, 0));
    assertThat(scenario.links()).isEmpty();
  }

  @Test
  @DisplayName("연결 예외가 나도 terminal run과 점유 반환을 보존한다")
  protected void reconciliationFailureDoesNotReopenRun() {
    scenario.reconciliationThrows();
    var done = scenario.collect();
    // then
    assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(scenario.atReconciliation())
        .containsExactly(new CompletionState(RunStatus.SUCCEEDED, true, false, 0));
    assertThat(scenario.afterCollection())
        .isEqualTo(new CompletionState(RunStatus.SUCCEEDED, true, false, 0));
  }

  @Test
  @DisplayName("첫 소스의 연결 실패 뒤에도 다음 소스가 연결된다")
  protected void failureInOneSourceDoesNotSkipOtherSources() {
    scenario.reconciliationThrows();
    scenario.collectBothSources();
    // then
    assertThat(scenario.links())
        .extracting(link -> link.source().value())
        .containsExactly("slack-b");
  }

  @Test
  @DisplayName("heartbeat 시작 실패로 마감한 run도 점유 반환 뒤 연결 정비한다")
  protected void heartbeatStartFailureReconcilesAfterRelease() {
    scenario.rejectHeartbeats();
    // when·then
    assertThatThrownBy(scenario::collect)
        .isInstanceOf(TaskRejectedException.class)
        .hasMessageContaining("scheduler unavailable");
    assertThat(scenario.atReconciliation())
        .containsExactly(new CompletionState(RunStatus.FAILED, false, false, 0));
  }

  @Test
  @DisplayName("연결 성공 이벤트는 소스와 집계만 기록한다")
  protected void successLogsOnlySourceAndCounts() {
    try (var log = LogCapture.of(CollectionRuntime.class)) {
      scenario.collect();
      // then
      assertThat(log.events("admin.identity_reconcile.completed"))
          .containsExactly(
              new ObjectMapper()
                  .readTree(
                      "{\"level\":\"info\",\"event\":\"admin.identity_reconcile.completed\","
                          + "\"sourceInstanceId\":\"slack-a\",\"detail\":{\"linked\":1,\"unlinked\":0,"
                          + "\"unchanged\":0,\"failedSources\":0}}"));
    }
  }

  @Test
  @DisplayName("연결 예외는 원문 없이 고정 실패 이벤트를 남긴다")
  protected void failureLogsFixedEventWithoutPrivateMessage() {
    scenario.reconciliationThrows();
    try (var log = LogCapture.of(CollectionRuntime.class)) {
      scenario.collect();
      // then
      assertThat(log.events("admin.identity_reconcile.failed"))
          .containsExactly(
              new ObjectMapper()
                  .readTree(
                      "{\"level\":\"warn\",\"event\":\"admin.identity_reconcile.failed\","
                          + "\"sourceInstanceId\":\"slack-a\",\"detail\":{\"linked\":0,\"unlinked\":0,"
                          + "\"unchanged\":0,\"failedSources\":1}}"));
    }
  }

  @Test
  @DisplayName("실패 소스가 담긴 보고도 실패 이벤트로 기록한다")
  protected void failedReportLogsFailure() {
    scenario.reconciliationReportsFailure();
    try (var log = LogCapture.of(CollectionRuntime.class)) {
      scenario.collect();
      // then
      assertThat(log.events("admin.identity_reconcile.failed"))
          .singleElement()
          .satisfies(
              event -> assertThat(event.path("detail").path("failedSources").asInt()).isOne());
    }
  }

  protected record CompletionState(
      RunStatus status, boolean observedIdentity, boolean claimed, long liveHeartbeats) {}

  /** 실제 저장소와 runtime을 공유하며 콜백 진입 시 공개 저장 상태를 관찰한다. */
  public static final class Scenario {
    private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
    private static final SourceInstanceId SECOND = new SourceInstanceId("slack-b");
    private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
    private static final DomainPolicy DOMAINS = new DomainPolicy(Set.of("example.com"), Map.of());
    private final MutableClock clock = new MutableClock(AT);
    private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
    private final CommitContext.Ports ports;
    private final AdminStore admin;
    private final IdentityReconciler reconciler;
    private final Supplier<CollectionRuntime> runtime;
    private boolean rejectHeartbeats;
    private final List<CompletionState> atReconciliation = new ArrayList<>();
    private boolean guest;
    private boolean bodyFails;
    private boolean throwsOnFirstSource;
    private boolean reportsFailure;
    private int runNumber;
    private Runnable failTerminal;
    private Runnable cleanup = () -> {};
    private boolean changedEmail;

    private Scenario(CommitContext.Memory memory) {
      ports = memory.ports();
      failTerminal = memory.runs()::failTerminalWrites;
      var identities = memory.runs().identities();
      admin =
          new MemoryAdminStore(
              identities,
              new MemoryUserTokens(identities),
              new MemorySourceAccessDirectory(identities));
      reconciler = observing(AdminConfiguration.newIdentityReconciler(admin, clock, DOMAINS));
      runtime =
          () ->
              MemoryCollectionRuntime.over(clock, memory.sources(), memory.claims(), memory.runs())
                  .adapter(new Adapter())
                  .collectAccess(true)
                  .heartbeats(
                      rejectHeartbeats
                          ? ManualTaskScheduler.rejecting("scheduler unavailable")
                          : scheduler)
                  .identityReconciler(reconciler)
                  .runIds(this::nextRun)
                  .build();
      seed();
    }

    private Scenario(DataSource ds) {
      ports = CommitContext.Ports.postgres(ds);
      configureTerminalFault(ds);
      admin = new PostgresIndexConfiguration().newAdminStore(ds);
      reconciler = observing(AdminConfiguration.newIdentityReconciler(admin, clock, DOMAINS));
      runtime =
          () ->
              PostgresCollectionRuntime.over(ds, clock)
                  .adapter(new Adapter())
                  .collectAccess(true)
                  .heartbeats(
                      rejectHeartbeats
                          ? ManualTaskScheduler.rejecting("scheduler unavailable")
                          : scheduler)
                  .domains(DOMAINS)
                  .identityReconciler(reconciler)
                  .runIds(this::nextRun)
                  .build();
      seed();
    }

    private void configureTerminalFault(DataSource ds) {
      cleanup =
          () -> {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("DROP TRIGGER IF EXISTS fail_identity_terminal ON collection_runs");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_identity_terminal()");
          };
      failTerminal =
          () -> {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                """
            CREATE FUNCTION fail_identity_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected terminal commit failure'; END $$
            """);
            jdbc.execute(
                """
            CREATE TRIGGER fail_identity_terminal BEFORE UPDATE ON collection_runs
            FOR EACH ROW WHEN (NEW.status <> 'running') EXECUTE FUNCTION fail_identity_terminal()
            """);
          };
    }

    public static Scenario memory() {
      return new Scenario(CommitContext.Memory.create());
    }

    public static Scenario postgres(DataSource ds) {
      return new Scenario(ds);
    }

    private void seed() {
      ports.sources().save(SourceInstances.of(SOURCE, "slack", "{}", "env:FAKE", AT));
      ports.sources().save(SourceInstances.of(SECOND, "slack", "{}", "env:FAKE", AT));
      admin.transact(
          session -> {
            session.insertUser(
                new UserAccount("alice", "alice@example.com", true, Role.MEMBER, AT));
            return null;
          });
    }

    private RunId nextRun() {
      return new RunId("identity-run-" + ++runNumber);
    }

    private IdentityReconciler observing(IdentityReconciler delegate) {
      return new IdentityReconciler() {
        @Override
        public ReconcileReport reconcile(SourceInstanceId source) {
          atReconciliation.add(
              new CompletionState(
                  ports.runs().latest(source).orElseThrow().status(),
                  admin.transact(s -> !s.observedIdentities(source).isEmpty()),
                  ports
                      .claims()
                      .current(source)
                      .filter(c -> c.isActiveAt(clock.instant()))
                      .isPresent(),
                  scheduler.liveTasks()));
          if (source.equals(SOURCE) && throwsOnFirstSource)
            throw new IllegalStateException("alice@example.com user:slack-a:U1");
          if (reportsFailure) return new ReconcileReport(0, 0, 0, 1);
          return delegate.reconcile(source);
        }

        @Override
        public ReconcileReport reconcileAll(CancellationToken cancel) {
          return delegate.reconcileAll(cancel);
        }
      };
    }

    void givenAutoLink() {
      admin.transact(
          session -> {
            session.putLink(
                new IdentityLink(SOURCE, "U1", "alice", LinkMethod.AUTO, AT, "system", Set.of()));
            return null;
          });
      if (links().size() != 1) throw new IllegalStateException("given 준비 실패: auto 연결이 있어야 한다");
    }

    CompletionState afterCollection() {
      return new CompletionState(
          storedStatus(),
          admin.transact(s -> !s.observedIdentities(SOURCE).isEmpty()),
          claimed(),
          liveHeartbeats());
    }

    void rejectHeartbeats() {
      rejectHeartbeats = true;
    }

    void changedEmailWithTerminalFailure() {
      changedEmail = true;
      failTerminal.run();
    }

    void guestWithFailedBody() {
      guest = true;
      bodyFails = true;
      clock.advance(Duration.ofSeconds(1));
    }

    void reconciliationThrows() {
      throwsOnFirstSource = true;
    }

    void reconciliationReportsFailure() {
      reportsFailure = true;
    }

    CollectionRuntime.Report collect() {
      return runtime.get().collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    }

    void collectBothSources() {
      collect();
      runtime.get().collect(SECOND, CollectionMode.INCREMENTAL, CancellationToken.none());
    }

    List<CompletionState> atReconciliation() {
      return List.copyOf(atReconciliation);
    }

    List<IdentityLink> links() {
      return admin.transact(s -> s.links(Optional.empty(), new PageRequest()).items());
    }

    RunStatus storedStatus() {
      return ports.runs().latest(SOURCE).orElseThrow().status();
    }

    boolean claimed() {
      return ports.claims().current(SOURCE).filter(c -> c.isActiveAt(clock.instant())).isPresent();
    }

    long liveHeartbeats() {
      return scheduler.liveTasks();
    }

    private final class Adapter implements SourceAdapter {
      @Override
      public String kind() {
        return "slack";
      }

      @Override
      public void validateConfig(SourceConfig config) {}

      @Override
      public CollectionCoverage coverage(
          SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
        return CollectionCoverage.INCREMENTAL;
      }

      @Override
      public void collect(CollectionInput input, PageSink sink) {
        if (bodyFails) throw new IllegalStateException("body unavailable");
      }

      @Override
      public AccessPolicy accessPolicy() {
        return AccessPolicy.supported(Set.of(), Set.of()).collecting(this::collectAccess);
      }

      private void collectAccess(AccessInput input, AclSink sink) {
        sink.accept(
            new AclPage(
                Map.of(),
                List.of(),
                List.of(
                    new SourceIdentityRecord(
                        "U1",
                        Optional.of(changedEmail ? "changed@example.com" : "alice@example.com"),
                        true,
                        guest,
                        false,
                        clock.instant()))));
      }
    }
  }
}
