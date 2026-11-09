package io.scos.collection.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminStore;
import io.scos.store.memory.MemoryIdentitySeed;
import io.scos.store.memory.MemorySourceAccessDirectory;
import io.scos.store.memory.MemoryUserTokens;
import io.scos.store.postgres.PostgresAccessSeed;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** 등록 쓰기와 관리 읽기의 실제 어댑터 조립. 별도 소스 seed는 두지 않는다. */
public final class SourceRegistrationAdminScenario {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");
  private final SourceInstanceStore sources;
  private final AdminStore admin;
  private final RunStore runs;
  private final AccessObservationStore observations;
  private final Consumer<AccessDirectory> seed;
  private final Optional<DataSource> database;

  private SourceRegistrationAdminScenario(
      SourceInstanceStore sources,
      AdminStore admin,
      RunStore runs,
      AccessObservationStore observations,
      Consumer<AccessDirectory> seed,
      Optional<DataSource> database) {
    this.sources = sources;
    this.admin = admin;
    this.runs = runs;
    this.observations = observations;
    this.seed = seed;
    this.database = database;
  }

  public static SourceRegistrationAdminScenario memory() {
    var runs = MemoryRunStore.create();
    var identities = runs.identities();
    var tokens = new MemoryUserTokens(identities);
    var directory = new MemorySourceAccessDirectory(identities);
    return new SourceRegistrationAdminScenario(
        new MemorySourceInstanceStore(identities),
        new MemoryAdminStore(identities, tokens, directory),
        runs,
        runs.accessObservations(),
        new MemoryIdentitySeed(identities)::seed,
        Optional.empty());
  }

  public static SourceRegistrationAdminScenario postgres(DataSource dataSource) {
    var factory = new PostgresIndexConfiguration();
    var adapters = PostgresCollectionConfiguration.adapters(dataSource);
    return new SourceRegistrationAdminScenario(
        adapters.sources(),
        factory.newAdminStore(dataSource),
        adapters.runs(),
        adapters.accessObservations(),
        new PostgresAccessSeed(dataSource)::seed,
        Optional.of(dataSource));
  }

  void register(String kind) {
    sources.save(
        new SourceInstance(
            SOURCE,
            kind,
            "Contract source",
            new SourceConfig(1, "{}"),
            "env:CONTRACT",
            true,
            SourceSchedule.DAEMON_DEFAULTS,
            SnapshotShrinkOverrides.DAEMON_DEFAULTS,
            AT,
            AT));
  }

  void seedAndMerge() {
    seed.accept(
        new AccessDirectory(
            List.of(new AccessDirectory.User("alice", "alice@example.com", true)),
            List.of(),
            List.of(new AccessDirectory.Link(SOURCE, "U01", "alice", "admin")),
            List.of(),
            Set.of()));
    stageIdentity("alice@example.com");
    merge(() -> {});
  }

  void stageIdentity(String email) {
    var run = new RunId("admin-directory-merge");
    runs.start(run, SOURCE, AT);
    observations.stage(
        run,
        new AclPage(
            Map.of(),
            List.of(),
            List.of(new SourceIdentityRecord("U01", Optional.of(email), true, false, false, AT))));
  }

  AccessMerge merge(Runnable guard) {
    return observations.merge(
        new RunId("admin-directory-merge"),
        SOURCE,
        AccessMerge.COMPLETE,
        IdentityCoverage.PARTIAL,
        AT,
        guard);
  }

  List<SourceIdentityRecord> observedIdentities() {
    return admin.transact(session -> session.observedIdentities(SOURCE)).stream()
        .map(value -> value.identity())
        .toList();
  }

  List<SourceIdentityRecord> readLockedSource(Runnable started) {
    started.run();
    return admin.transact(
        session -> {
          session.lockAdministration();
          session.lockSource(SOURCE);
          return session.observedIdentities(SOURCE).stream()
              .map(value -> value.identity())
              .toList();
        });
  }

  DirectoryMergeRace mergeRace() {
    return new DirectoryMergeRace(this, database);
  }

  Optional<SourceAccount> source() {
    return admin.transact(session -> session.source(SOURCE));
  }
}
