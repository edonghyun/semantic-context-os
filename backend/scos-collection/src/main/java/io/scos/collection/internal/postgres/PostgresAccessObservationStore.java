package io.scos.collection.internal.postgres;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.AccessMergePolicy;
import io.scos.collection.internal.AccessMergePolicy.Observations;
import io.scos.collection.internal.AccessObservationStore;
import io.scos.collection.internal.RunId;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** run 잠금은 페이지·병합·폐기를, source 잠금은 서로 다른 run의 확정을 직렬화한다. */
public final class PostgresAccessObservationStore implements AccessObservationStore {
  private final JdbcClient jdbc;
  private final SourceAccessDirectory directory;
  private final TransactionTemplate staging;
  private final TransactionTemplate merging;
  private final JsonMapper json = JsonMapper.builder().build();

  public PostgresAccessObservationStore(
      JdbcClient jdbc, PlatformTransactionManager manager, SourceAccessDirectory directory) {
    this.directory = directory;
    this.jdbc = jdbc;
    staging = new TransactionTemplate(manager);
    merging = new TransactionTemplate(manager);
    merging.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void stage(RunId run, AclPage page) {
    staging.executeWithoutResult(
        status -> {
          lockRun(run);
          for (GroupSnapshot group : page.groups())
            stageRow(
                run, "group", group.groupId(), new AclPage(Map.of(), List.of(group), List.of()));
          for (SourceIdentityRecord identity : page.identities())
            stageRow(
                run,
                "identity",
                identity.sourceUserId(),
                new AclPage(Map.of(), List.of(), List.of(identity)));
        });
  }

  private void stageRow(RunId run, String kind, String key, AclPage page) {
    Observations next = stagedRow(run, kind, key).observe(page);
    saveStagedRow(run, kind, key, next);
  }

  private Observations stagedRow(RunId run, String kind, String key) {
    return jdbc.sql(
            """
        SELECT kind, payload::text, conflicted FROM access_observations
        WHERE run_id = :run AND kind = :kind AND key = :key FOR UPDATE
        """)
        .param("run", run.value())
        .param("kind", kind)
        .param("key", key)
        .query(
            (rs, row) ->
                decode(rs.getString("kind"), rs.getString("payload"), rs.getBoolean("conflicted")))
        .optional()
        .orElse(Observations.empty());
  }

  private void saveStagedRow(RunId run, String kind, String key, Observations next) {
    Object value =
        kind.equals("group")
            ? stagedGroup(Objects.requireNonNull(next.snapshot().groups().get(key)))
            : next.snapshot().identities().get(key);
    Instant observed =
        kind.equals("group")
            ? Objects.requireNonNull(next.snapshot().groups().get(key)).observedAt()
            : Objects.requireNonNull(next.snapshot().identities().get(key)).observedAt();
    jdbc.sql(
            """
        INSERT INTO access_observations (run_id, kind, key, payload, observed_at, conflicted)
        VALUES (:run, :kind, :key, CAST(:payload AS jsonb), :at, :conflicted)
        ON CONFLICT (run_id, kind, key) DO UPDATE SET
          payload = EXCLUDED.payload, observed_at = EXCLUDED.observed_at, conflicted = EXCLUDED.conflicted
        """)
        .param("run", run.value())
        .param("kind", kind)
        .param("key", key)
        .param("payload", json.writeValueAsString(value))
        .param("at", Timestamp.from(observed))
        .param("conflicted", next.conflicted())
        .update();
  }

  @Override
  public AccessMerge merge(
      RunId run,
      SourceInstanceId source,
      AccessMerge requested,
      IdentityCoverage identityCoverage,
      Instant at,
      Runnable guard) {
    if (!new AccessKeyInstances().admits(source))
      throw new IllegalArgumentException("Invalid access source instance");
    try {
      return mergeOnce(run, source, requested, identityCoverage, at, guard);
    } catch (AccessMergePolicy.IncompleteSnapshotException incomplete) {
      return mergeOnce(run, source, AccessMerge.INCOMPLETE, identityCoverage, at, guard);
    }
  }

  private AccessMerge mergeOnce(
      RunId run,
      SourceInstanceId source,
      AccessMerge requested,
      IdentityCoverage identityCoverage,
      Instant at,
      Runnable guard) {
    return Objects.requireNonNull(
        merging.execute(
            status -> {
              lockObservations(run);
              guard.run();
              String runSource = lockRun(run);
              if (!runSource.equals(source.value()))
                throw new IllegalArgumentException("Run belongs to another source");
              Observations observed = observations(run);
              AccessMerge mode = observed.conflicted() ? AccessMerge.INCOMPLETE : requested;
              if (mode == AccessMerge.INCOMPLETE && observed.snapshot().equals(Snapshot.empty()))
                return mode;
              jdbc.sql("SELECT id FROM source_instances WHERE id = :source FOR UPDATE")
                  .param("source", source.value())
                  .query(String.class)
                  .single();
              var next =
                  AccessMergePolicy.merge(
                      mode, identityCoverage, directory.current(source), observed.snapshot(), at);
              directory.save(source, next.snapshot());
              return next.mode();
            }));
  }

  private String lockRun(RunId run) {
    return jdbc.sql("SELECT source_instance_id FROM collection_runs WHERE run_id = :run FOR UPDATE")
        .param("run", run.value())
        .query(String.class)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Run not found"));
  }

  private Observations observations(RunId run) {
    var rows =
        jdbc.sql(
                "SELECT kind, payload::text, conflicted FROM access_observations WHERE run_id = :run FOR UPDATE")
            .param("run", run.value())
            .query(
                (rs, row) ->
                    decode(
                        rs.getString("kind"), rs.getString("payload"), rs.getBoolean("conflicted")))
            .list();
    var identities = new HashMap<String, SourceIdentityRecord>();
    var groups = new HashMap<String, GroupSnapshot>();
    for (Observations row : rows) {
      identities.putAll(row.snapshot().identities());
      groups.putAll(row.snapshot().groups());
    }
    return new Observations(
        new Snapshot(identities, groups), rows.stream().anyMatch(Observations::conflicted));
  }

  private record StagedGroup(String groupId, Set<String> members, Instant observedAt) {
    GroupSnapshot observation() {
      return groupId.startsWith("~")
          ? GroupSnapshot.synthetic(groupId, members, observedAt)
          : new GroupSnapshot(groupId, members, observedAt);
    }
  }

  private StagedGroup stagedGroup(GroupSnapshot group) {
    return new StagedGroup(group.groupId(), group.members(), group.observedAt());
  }

  private Observations decode(String kind, String payload, boolean conflicted) {
    if (kind.equals("group")) {
      var stored = json.readValue(payload, StagedGroup.class);
      var group = stored.observation();
      return new Observations(new Snapshot(Map.of(), Map.of(group.groupId(), group)), conflicted);
    }
    var identity = json.readValue(payload, SourceIdentityRecord.class);
    return new Observations(
        new Snapshot(Map.of(identity.sourceUserId(), identity), Map.of()), conflicted);
  }

  /** 각 병합 트랜잭션의 잠금은 rollback 때도 자동 해제된다. fallback은 guard와 관측을 새로 읽는다. */
  private void lockObservations(RunId run) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "scos.access:" + run.value())
        .query((rs, row) -> true)
        .single();
  }

  @Override
  public void discard(RunId run) {
    staging.executeWithoutResult(
        status -> {
          lockObservations(run);
          jdbc.sql("SELECT run_id FROM collection_runs WHERE run_id = :run FOR UPDATE")
              .param("run", run.value())
              .query(String.class)
              .list();
          jdbc.sql("DELETE FROM access_observations WHERE run_id = :run")
              .param("run", run.value())
              .update();
        });
  }
}
