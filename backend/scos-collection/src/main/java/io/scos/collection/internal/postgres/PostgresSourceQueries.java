package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RawCatalogCursor;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunHistory;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaim;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 운영 조회. 문자열 정렬·비교는 COLLATE "C"(코드 유닛 순). 행 매핑은 쓰기 어댑터의 것을 그대로 쓴다. */
public final class PostgresSourceQueries implements SourceQueries {
  private static final String RAW_COLUMNS = "source_instance_id, " + PostgresRawItemStore.COLUMNS;
  private static final String REJECTION_COLUMNS =
      "run_id, captured_at, observed_at, failure_kind, message, provider_locator AS locator, quarantined_storage, quarantined_media_type, quarantined_inline, quarantined_sha256, quarantined_size";

  private final JdbcClient jdbc;
  private final SnapshotGuardStore guards;

  public PostgresSourceQueries(JdbcClient jdbc, SnapshotGuardStore guards) {
    this.jdbc = jdbc;
    this.guards = guards;
  }

  @Override
  public List<SourceInstance> listSources() {
    return jdbc.sql(
            "SELECT "
                + PostgresSourceInstanceStore.COLUMNS
                + " FROM source_instances ORDER BY id COLLATE \"C\"")
        .query(PostgresSourceInstanceStore::map)
        .list();
  }

  @Override
  public Optional<SourceStatus> status(SourceInstanceId source, Instant now) {
    return instance(source).map(instance -> statusOf(source, instance, now));
  }

  private SourceStatus statusOf(SourceInstanceId source, SourceInstance instance, Instant now) {
    List<CollectionRun> runs = runsNewestFirst(source);
    Map<String, Integer> itemCounts = itemCountsByState(source);
    Optional<Instant> lease = activeLeaseExpiry(source, now);
    Optional<RunStore.SnapshotCompletion> snapshot = lastSnapshotCompletion(source);
    return new SourceStatus(
        source,
        instance.enabled(),
        runs.stream().anyMatch(run -> run.status() == RunStatus.RUNNING),
        itemCounts.getOrDefault("active", 0),
        itemCounts.getOrDefault("deleted", 0),
        RunHistory.of(runs).failuresSinceLastSuccess(),
        lease,
        snapshot,
        runs.stream().findFirst().map(PostgresSourceQueries::lastRun),
        runs.stream()
            .filter(run -> run.status().isSuccess())
            .findFirst()
            .flatMap(CollectionRun::finishedAt),
        guards.lastGuarded(source),
        pendingAcceptance(source),
        instance.shrinkGuard());
  }

  private Optional<SourceInstance> instance(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT "
                + PostgresSourceInstanceStore.COLUMNS
                + " FROM source_instances WHERE id = :source")
        .param("source", source.value())
        .query(PostgresSourceInstanceStore::map)
        .optional();
  }

  private List<CollectionRun> runsNewestFirst(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT "
                + PostgresRunStore.COLUMNS
                + " FROM collection_runs WHERE source_instance_id = :source"
                + PostgresRunStore.NEWEST_FIRST)
        .param("source", source.value())
        .query(PostgresRunStore::map)
        .list();
  }

  private Map<String, Integer> itemCountsByState(SourceInstanceId source) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT state, COUNT(*) AS n FROM raw_items WHERE source_instance_id = :source GROUP BY state")
        .param("source", source.value())
        .query((RowCallbackHandler) rs -> counts.put(rs.getString("state"), rs.getInt("n")));
    return counts;
  }

  private Optional<Instant> activeLeaseExpiry(SourceInstanceId source, Instant now) {
    return jdbc.sql(
            "SELECT expires_at FROM work_claims WHERE source_instance_id = :source AND expires_at > :now")
        .param("source", source.value())
        .param("now", Timestamp.from(now))
        .query((rs, i) -> rs.getTimestamp("expires_at").toInstant())
        .optional();
  }

  private Optional<RunStore.SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source) {
    return jdbc.sql(PostgresRunStore.SNAPSHOT_COMPLETION)
        .param("source", source.value())
        .query(PostgresRunStore::snapshotCompletion)
        .optional();
  }

  private Optional<Instant> pendingAcceptance(SourceInstanceId source) {
    return guards
        .lastAcceptance(source)
        .filter(SnapshotGuardStore.Acceptance::pending)
        .map(SnapshotGuardStore.Acceptance::acceptedAt);
  }

  private static LastRun lastRun(CollectionRun run) {
    return new LastRun(run.startedAt(), run.status(), run.errorKind(), run.errorSummary());
  }

  @Override
  public List<RecentRejection> recentRejections(SourceInstanceId source, int limit) {
    return jdbc.sql(
            "SELECT "
                + REJECTION_COLUMNS
                + " FROM raw_rejections WHERE source_instance_id = :source ORDER BY captured_at DESC, id DESC LIMIT :limit")
        .param("source", source.value())
        .param("limit", limit)
        .query(
            (rs, i) ->
                new RecentRejection(
                    new RunId(rs.getString("run_id")),
                    rs.getTimestamp("captured_at").toInstant(),
                    PostgresRunStore.rejection(rs, i)))
        .list();
  }

  @Override
  public RawCatalogPage rawList(RawCatalogQuery query) {
    List<String> predicates = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    query
        .source()
        .ifPresent(
            source -> {
              predicates.add("source_instance_id = :source");
              params.put("source", source.value());
            });
    query
        .resourceType()
        .ifPresent(
            type -> {
              predicates.add("resource_type = :type");
              params.put("type", type);
            });
    query
        .state()
        .ifPresent(
            state -> {
              predicates.add("state = :state");
              params.put("state", state.wire());
            });
    query
        .cursor()
        .ifPresent(
            cursor -> {
              predicates.add(
                  "(observed_at < :at OR (observed_at = :at AND source_instance_id COLLATE \"C\" > :cursorSource)"
                      + " OR (observed_at = :at AND source_instance_id = :cursorSource AND external_id COLLATE \"C\" > :cursorId))");
              params.put("at", Timestamp.from(cursor.observedAt()));
              params.put("cursorSource", cursor.source().value());
              params.put("cursorId", cursor.externalId());
            });
    String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    List<RawEntry> rows =
        jdbc.sql(
                "SELECT "
                    + RAW_COLUMNS
                    + " FROM raw_items"
                    + where
                    + " ORDER BY observed_at DESC, source_instance_id COLLATE \"C\", external_id COLLATE \"C\" LIMIT :limit")
            .params(params)
            .param("limit", query.limit() + 1)
            .query(
                (rs, i) ->
                    new RawEntry(
                        new SourceInstanceId(rs.getString("source_instance_id")),
                        PostgresRawItemStore.map(rs, i)))
            .list();
    if (rows.size() <= query.limit()) return new RawCatalogPage(rows, Optional.empty());
    List<RawEntry> page = rows.subList(0, query.limit());
    RawEntry last = page.getLast();
    Instant observedAt =
        switch (last.record()) {
          case RawRecord.Active active -> active.item().observedAt();
          case RawRecord.Deleted deleted -> deleted.observedAt();
        };
    return new RawCatalogPage(
        List.copyOf(page),
        Optional.of(new RawCatalogCursor(observedAt, last.source(), last.record().externalId())));
  }

  @Override
  public Optional<RawEntry> rawGet(SourceInstanceId source, String externalId) {
    return jdbc.sql(
            "SELECT "
                + RAW_COLUMNS
                + " FROM raw_items WHERE source_instance_id = :source AND external_id = :id")
        .param("source", source.value())
        .param("id", externalId)
        .query((rs, i) -> new RawEntry(source, PostgresRawItemStore.map(rs, i)))
        .optional();
  }

  @Override
  public List<CollectionRun> recentRuns(int limit) {
    return jdbc.sql(
            "SELECT "
                + PostgresRunStore.COLUMNS
                + " FROM collection_runs"
                + PostgresRunStore.NEWEST_FIRST
                + " LIMIT :limit")
        .param("limit", limit)
        .query(PostgresRunStore::map)
        .list();
  }

  @Override
  public List<WorkClaim> claims() {
    return jdbc.sql(
            "SELECT source_instance_id, owner_id, fencing_token, expires_at FROM work_claims ORDER BY source_instance_id COLLATE \"C\"")
        .query(PostgresWorkClaimAdapter.ClaimRow::map)
        .list();
  }
}
