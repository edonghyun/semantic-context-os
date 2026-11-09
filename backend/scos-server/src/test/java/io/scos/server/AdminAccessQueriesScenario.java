package io.scos.server;

import io.scos.boot.AdminConfiguration;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.FixedSourceQueries;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries.AccessExplanation;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.collection.internal.SourceQueries.LastRun;
import io.scos.collection.internal.SourceQueries.RawAccess;
import io.scos.collection.internal.SourceQueries.RunCost;
import io.scos.collection.internal.SourceQueries.SourceStatus;
import io.scos.engine.admin.AdminDiagnostics.DocumentAccessView;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.engine.admin.AdminPort;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 같은 관리·신원 상태를 실제 설명기와 관리 팩터리에 연결한다. */
final class AdminAccessQueriesScenario {
  static final Instant T0 = Instant.parse("2026-11-08T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final AccessKey KEY = AccessKey.parse("user:drive-a:reader@example.com");
  private static final DomainPolicy DOMAINS = new DomainPolicy(Set.of("example.com"), Map.of());
  private static final Principal ADMIN = new Principal("admin");
  private final MemoryAdminScenario storage = new MemoryAdminScenario();
  private Optional<DocumentAcl> acl =
      Optional.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(KEY), T0));
  private List<RawAccess> carriers = List.of();
  private boolean knownDocument = true;
  private boolean running;
  private boolean neverRun;

  AdminAccessQueriesScenario() {
    storage.registerSource(new SourceAccount(SOURCE, "google-drive"));
    storage
        .store()
        .transact(
            session -> {
              session.insertUser(
                  new UserAccount("admin", "admin@example.com", true, Role.ADMIN, T0));
              session.insertUser(
                  new UserAccount("reader", "reader@example.com", true, Role.MEMBER, T0));
              return true;
            });
  }

  void givenManualUnobservedLink() {
    port().linkIdentity(ADMIN, "reader", SOURCE, "reader@example.com");
  }

  void givenObservedReader() {
    observe(false, false);
    givenManualUnobservedLink();
  }

  void givenGuestAdminLink() {
    observe(true, false);
    givenManualUnobservedLink();
  }

  void givenDeletedAdminLink() {
    observe(false, true);
    givenManualUnobservedLink();
  }

  void givenAnotherGuestObservation() {
    storage.observeIdentity(
        SOURCE,
        new SourceIdentityRecord(
            "other@example.com", Optional.of("other@example.com"), true, true, false, T0));
  }

  private void observe(boolean guest, boolean deleted) {
    storage.observeIdentity(
        SOURCE,
        new SourceIdentityRecord(
            "reader@example.com", Optional.of("reader@example.com"), true, guest, deleted, T0));
  }

  void givenFailedAcl(AccessFailureReason reason) {
    acl = Optional.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), T0, reason));
    carriers = List.of(new RawAccess("file:F1", RawAcl.failed(reason, T0)));
  }

  void givenNeverRun() {
    neverRun = true;
  }

  void givenManyLinks() {
    storage
        .store()
        .transact(
            session -> {
              for (int index = 0; index < 51; index++) {
                session.putLink(
                    new IdentityLink(
                        SOURCE,
                        "reader-" + index + "@example.com",
                        "reader",
                        LinkMethod.ADMIN,
                        T0,
                        "admin",
                        Set.of()));
              }
              return true;
            });
  }

  void givenOtherSourceLink() {
    var other = new SourceInstanceId("drive-b");
    storage.registerSource(new SourceAccount(other, "google-drive"));
    port().linkIdentity(ADMIN, "reader", other, "reader@example.com");
  }

  void givenRunningRun() {
    running = true;
  }

  void givenMissingAcl() {
    acl = Optional.empty();
  }

  void givenUnknownDocument() {
    knownDocument = false;
  }

  List<SourceAccessView> sourceAccess() {
    return port().sourceAccess(ADMIN);
  }

  Optional<DocumentAccessView> explain(Optional<String> user) {
    return port().explainAccess(ADMIN, "doc", user);
  }

  private AdminPort port() {
    var queries = queries();
    var diagnostics =
        new AdminAccessQueries(
            queries,
            new AccessExplainer(queries, storage.identityReader(), DOMAINS),
            storage.store(),
            CLOCK);
    return AdminConfiguration.newAdminPort(storage.store(), CLOCK, DOMAINS, diagnostics);
  }

  private FixedSourceQueries queries() {
    var document = new AccessExplanation("doc", SOURCE, SpaceId.LEGACY, acl, carriers);
    var body =
        new SourceStatus(
            SOURCE,
            true,
            running,
            2,
            0,
            0,
            Optional.empty(),
            Optional.empty(),
            neverRun
                ? Optional.empty()
                : Optional.of(
                    new LastRun(
                        T0,
                        running ? RunStatus.RUNNING : RunStatus.SUCCEEDED,
                        Optional.empty(),
                        Optional.empty())),
            Optional.of(T0),
            Optional.empty(),
            Optional.empty(),
            SnapshotShrinkOverrides.DAEMON_DEFAULTS,
            0);
    return new FixedSourceQueries(
        List.of(SourceInstances.of(SOURCE, "google-drive", "{}", "env:SYNTHETIC", T0)),
        Map.of(SOURCE, status()),
        knownDocument ? Map.of("doc", document) : Map.of(),
        Map.of(SOURCE, body));
  }

  private AccessStatus status() {
    if (neverRun)
      return new AccessStatus(
          Optional.empty(),
          Optional.empty(),
          0,
          0,
          0,
          0,
          0,
          Optional.empty(),
          false,
          0,
          Optional.empty(),
          0,
          Set.of(),
          Optional.empty());
    return new AccessStatus(
        Optional.of(running ? AccessResult.SKIPPED : AccessResult.INCOMPLETE),
        running ? Optional.empty() : Optional.of(T0),
        1,
        2,
        0,
        3,
        4,
        Optional.of(T0),
        running,
        2,
        Optional.of(T0),
        2,
        Set.of("file:F1"),
        running
            ? Optional.empty()
            : Optional.of(
                new RunCost(new RunId("run-r1"), T0, new AccessCost(2, OptionalLong.of(4), 1250))));
  }
}
