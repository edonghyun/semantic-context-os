package io.scos.collection.internal;

import static io.scos.collection.internal.AccessObservationStoreContract.T0;
import static io.scos.collection.internal.AccessObservationStoreContract.T1;
import static io.scos.collection.internal.AccessObservationStoreContract.T2;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.memory.MemoryAccessObservationStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.postgres.PostgresIdentityStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** 운영 조립과 같은 쓰기→읽기 경계를 계약에 연결한다. */
public abstract class AccessObservationScenario {
  static final SourceInstanceId SOURCE = new SourceInstanceId("access-source");
  protected final AccessObservationStore store;
  private final IdentityStore reader;
  protected RunId run = new RunId("access-run");
  private int sequence;

  AccessObservationScenario(AccessObservationStore store, IdentityStore reader) {
    this.store = store;
    this.reader = reader;
  }

  public static AccessObservationScenario memory() {
    var runs = MemoryRunStore.create();
    var reader = runs.identities();
    reader.replace(
        new MemoryIdentityStore.Directory(
            List.of(new MemoryIdentityStore.User("local-user", "local@example.com", true)),
            List.of(),
            List.of(new MemoryIdentityStore.Link(SOURCE, "U2", "local-user")),
            List.of(),
            Set.of()));
    var scenario = new MemoryScenario(runs, reader);
    scenario.start();
    return scenario;
  }

  private static final class MemoryScenario extends AccessObservationScenario {
    private final MemoryAccessObservationStore observations;
    private final MemoryRunStore runs;

    private MemoryScenario(MemoryRunStore runs, MemoryIdentityStore reader) {
      super(runs.accessObservations(), reader);
      this.observations = runs.accessObservations();
      this.runs = runs;
    }

    @Override
    String fingerprint(String group) {
      return observations.fingerprint(SOURCE, group);
    }

    @Override
    SourceAccessDirectory.Snapshot current() {
      return observations.snapshot(SOURCE);
    }

    @Override
    AccessMergePolicy.Observations staged() {
      return observations.staged(run);
    }

    @Override
    void start() {
      runs.start(run, SOURCE, T0);
    }

    @Override
    void finish() {
      runs.finish(
          run,
          RunOutcome.succeeded(0),
          CollectionCoverage.INCREMENTAL,
          T2,
          0,
          AccessResult.INCOMPLETE,
          Optional.of(T2));
    }

    @Override
    void startForeignRun(RunId other, SourceInstanceId source) {
      runs.start(other, source, T0);
    }
  }

  public static AccessObservationScenario postgres(DataSource ds) {
    var jdbc = JdbcClient.create(ds);
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    adapters.sources().save(SourceInstances.noop(SOURCE, T0));
    jdbc.sql("INSERT INTO users VALUES ('local-user', 'local@example.com', true, 'member', :at)")
        .param("at", Timestamp.from(T0))
        .update();
    jdbc.sql(
            "INSERT INTO user_source_identities VALUES (:source, 'U2', 'local-user', 'admin', :at, 'local-user')")
        .param("source", SOURCE.value())
        .param("at", Timestamp.from(T0))
        .update();
    var scenario = new PostgresScenario(ds, jdbc);
    scenario.start();
    return scenario;
  }

  private static final class PostgresScenario extends AccessObservationScenario {
    private final DataSource dataSource;
    private final JdbcClient jdbc;

    private PostgresScenario(DataSource dataSource, JdbcClient jdbc) {
      super(
          PostgresCollectionConfiguration.adapters(dataSource).accessObservations(),
          new PostgresIdentityStore(jdbc));
      this.dataSource = dataSource;
      this.jdbc = jdbc;
    }

    @Override
    String fingerprint(String group) {
      return jdbc.sql(
              "SELECT fingerprint FROM source_groups WHERE source_instance_id = :source AND group_id = :group")
          .param("source", SOURCE.value())
          .param("group", group)
          .query(String.class)
          .single();
    }

    @Override
    SourceAccessDirectory.Snapshot current() {
      return readSnapshot(jdbc);
    }

    @Override
    AccessMergePolicy.Observations staged() {
      return readStaged(jdbc, run);
    }

    @Override
    void start() {
      PostgresCollectionConfiguration.adapters(dataSource).runs().start(run, SOURCE, T0);
    }

    @Override
    void finish() {
      PostgresCollectionConfiguration.adapters(dataSource)
          .runs()
          .finish(
              run,
              RunOutcome.succeeded(0),
              CollectionCoverage.INCREMENTAL,
              T2,
              0,
              AccessResult.INCOMPLETE,
              Optional.of(T2));
    }

    @Override
    void startForeignRun(RunId other, SourceInstanceId source) {
      var ports = PostgresCollectionConfiguration.adapters(dataSource);
      ports.sources().save(SourceInstances.noop(source, T0));
      ports.runs().start(other, source, T0);
    }
  }

  abstract String fingerprint(String group);

  abstract SourceAccessDirectory.Snapshot current();

  abstract AccessMergePolicy.Observations staged();

  abstract void start();

  abstract void finish();

  abstract void startForeignRun(RunId other, SourceInstanceId source);

  void seedForeignAccess() {
    var foreign = new SourceInstanceId("foreign");
    var other = new RunId("foreign-run");
    startForeignRun(other, foreign);
    var at = T2.plusSeconds(1);
    store.stage(
        other,
        new AclPage(
            Map.of(),
            List.of(new GroupSnapshot("C1", Set.of("U9"), at)),
            List.of(
                AccessObservationStoreContract.identity("U9", at),
                new SourceIdentityRecord("U2", Optional.empty(), false, false, true, at))));
    store.merge(other, foreign, AccessMerge.COMPLETE, at, () -> {});
  }

  void stage(GroupSnapshot... groups) {
    stage(new AclPage(Map.of(), List.of(groups), List.of()));
  }

  void stage(SourceIdentityRecord... identities) {
    stage(new AclPage(Map.of(), List.of(), List.of(identities)));
  }

  void stage(GroupSnapshot group, SourceIdentityRecord identity) {
    stage(new AclPage(Map.of(), List.of(group), List.of(identity)));
  }

  void stage(GroupSnapshot group, SourceIdentityRecord first, SourceIdentityRecord second) {
    stage(new AclPage(Map.of(), List.of(group), List.of(first, second)));
  }

  void stage(GroupSnapshot first, GroupSnapshot second, SourceIdentityRecord identity) {
    stage(new AclPage(Map.of(), List.of(first, second), List.of(identity)));
  }

  void stage(AclPage page) {
    store.stage(run, page);
  }

  void nextRun() {
    run = new RunId("access-run-" + ++sequence);
    start();
  }

  AccessMerge merge(AccessMerge mode) {
    return merge(mode, () -> {});
  }

  AccessMerge merge(AccessMerge mode, Runnable guard) {
    return store.merge(run, SOURCE, mode, T2, guard);
  }

  void discard() {
    store.discard(run);
  }

  Map<String, GroupSnapshot> groups() {
    return current().groups();
  }

  GroupSnapshot group(String id) {
    return current().groups().get(id);
  }

  SourceIdentityRecord identity(String id) {
    return current().identities().get(id);
  }

  Set<String> keys() {
    return reader
        .accessKeysOf("local-user", new DomainPolicy(Set.of(), Map.of()))
        .orElseThrow()
        .stream()
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  void existing() {
    stage(
        new AclPage(
            Map.of(),
            List.of(
                AccessObservationStoreContract.group("C1", T0, "U1", "U2"),
                AccessObservationStoreContract.group("C2", T0, "U1")),
            List.of(
                AccessObservationStoreContract.identity("U1", T0),
                AccessObservationStoreContract.identity("U2", T0))));
    merge(AccessMerge.COMPLETE);
    nextRun();
  }

  void observed() {
    stage(
        new AclPage(
            Map.of(),
            List.of(AccessObservationStoreContract.group("C1", T1, "U2", "U3")),
            List.of(
                AccessObservationStoreContract.identity("U2", T1),
                AccessObservationStoreContract.identity("U3", T1))));
  }

  void conflict() {
    stage(AccessObservationStoreContract.group("C1", T1, "U2", "U3"));
    stage(AccessObservationStoreContract.group("C1", T1, "U2", "U3", "U4"));
    stage(
        AccessObservationStoreContract.identity("U2", T1),
        AccessObservationStoreContract.identity("U3", T1));
  }

  void incompleteSnapshot() {
    existing();
    stage(
        AccessObservationStoreContract.group("C1", T1, "U2"),
        AccessObservationStoreContract.identity("U2", T1));
    stage(AccessObservationStoreContract.group("C9", T1, "U9"));
  }

  private static SourceAccessDirectory.Snapshot readSnapshot(JdbcClient jdbc) {
    var identities =
        jdbc.sql("SELECT * FROM source_identities WHERE source_instance_id = :source")
            .param("source", SOURCE.value())
            .query(
                (rs, row) ->
                    new SourceIdentityRecord(
                        rs.getString("source_user_id"),
                        Optional.ofNullable(rs.getString("email")),
                        rs.getBoolean("email_verified"),
                        rs.getBoolean("guest"),
                        rs.getBoolean("deleted"),
                        rs.getTimestamp("observed_at").toInstant()))
            .list();
    var groups =
        jdbc.sql("SELECT * FROM source_groups WHERE source_instance_id = :source")
            .param("source", SOURCE.value())
            .query(
                (rs, row) ->
                    new GroupSnapshot(
                        rs.getString("group_id"),
                        Set.copyOf(Arrays.asList((String[]) rs.getArray("members").getArray())),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("group_id").startsWith("~")
                            ? GroupSnapshot.Kind.SYNTHETIC
                            : GroupSnapshot.Kind.REAL))
            .list();
    return new SourceAccessDirectory.Snapshot(
        identities.stream().collect(Collectors.toMap(SourceIdentityRecord::sourceUserId, i -> i)),
        groups.stream().collect(Collectors.toMap(GroupSnapshot::groupId, g -> g)));
  }

  private static AccessMergePolicy.Observations readStaged(JdbcClient jdbc, RunId run) {
    var rows =
        jdbc.sql(
                "SELECT kind, key, payload::text, observed_at, conflicted FROM access_observations WHERE run_id = :run")
            .param("run", run.value())
            .query(
                (rs, row) ->
                    Map.entry(
                        rs.getString("kind") + ":" + rs.getString("key"), rs.getString("payload")))
            .list();
    // payload는 Jackson의 record 표현이다. 기대값은 계약 본문이 직접 적는다.
    var mapper = JsonMapper.builder().build();
    var groups = new HashMap<String, GroupSnapshot>();
    var identities = new HashMap<String, SourceIdentityRecord>();
    for (var row : rows) {
      if (row.getKey().startsWith("group:")) {
        var payload = mapper.readTree(row.getValue());
        var members = mapper.convertValue(payload.path("members"), String[].class);
        String groupId = payload.path("groupId").asString();
        var value =
            new GroupSnapshot(
                groupId,
                Set.of(members),
                Instant.parse(payload.path("observedAt").asString()),
                groupId.startsWith("~") ? GroupSnapshot.Kind.SYNTHETIC : GroupSnapshot.Kind.REAL);
        groups.put(value.groupId(), value);
      } else {
        var value = mapper.readValue(row.getValue(), SourceIdentityRecord.class);
        identities.put(value.sourceUserId(), value);
      }
    }
    boolean conflict =
        jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM access_observations WHERE run_id = :run AND conflicted)")
            .param("run", run.value())
            .query(Boolean.class)
            .single();
    return new AccessMergePolicy.Observations(
        new SourceAccessDirectory.Snapshot(identities, groups), conflict);
  }
}
