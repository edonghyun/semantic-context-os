package io.scos.server;

import io.scos.boot.AdminConfiguration;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminScenario;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 실제 자동 연결 정책과 메모리 저장소 위에서 시계와 정비 tick만 제어한다. */
final class IdentityLinkScheduleScenario {
  private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");
  private final MutableClock clock = new MutableClock(AT);
  private final MemoryAdminScenario storage = new MemoryAdminScenario();
  private final AdminStore store = storage.store();
  private final CancellationToken cancel = CancellationToken.none();
  private ManualTaskScheduler scheduler;
  private IdentityLinkMaintenance maintenance;
  private boolean storageFails;

  IdentityLinkScheduleScenario() {
    store.transact(
        session -> {
          session.insertUser(new UserAccount("alice", "alice@example.com", true, Role.MEMBER, AT));
          return null;
        });
    restart();
  }

  void eligibleIdentity(String source, String id) {
    var instance = new SourceInstanceId(source);
    storage.registerSource(new SourceAccount(instance, "slack"));
    storage.observeIdentity(
        instance,
        new SourceIdentityRecord(
            id, Optional.of("alice@example.com"), true, false, false, clock.instant()));
  }

  void advance(Duration duration) {
    clock.advance(duration);
  }

  void tick() {
    scheduler.tick();
  }

  void cancel() {
    cancel.cancel();
  }

  void failStorage() {
    storageFails = true;
  }

  void recoverStorage() {
    storageFails = false;
  }

  IdentityLinkMaintenance maintenance() {
    return maintenance;
  }

  void restart() {
    AdminStore guarded =
        new AdminStore() {
          @Override
          public <T> T transact(Function<Session, T> work) {
            if (storageFails) throw new IllegalStateException("alice@example.com user:slack-a:U1");
            return store.transact(work);
          }
        };
    maintenance =
        new IdentityLinkMaintenance(
            AdminConfiguration.newIdentityReconciler(
                guarded, clock, new DomainPolicy(Set.of("example.com"), Map.of())),
            clock);
    scheduler = new ManualTaskScheduler();
    var unused =
        scheduler.scheduleAtFixedRate(() -> maintenance.tick(cancel), Duration.ofSeconds(5));
  }

  List<IdentityLink> links() {
    return store.transact(session -> session.links(Optional.empty(), new PageRequest()).items());
  }
}
