package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunHistory;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunOutcome;
import io.scos.collection.internal.RunStateException;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresRunStore implements RunStore {
  static final String COLUMNS =
      "run_id, source_instance_id, status, started_at, finished_at, pages_applied, upserted, deleted, unchanged, rejected, error_count, error_kind, error_summary, coverage";

  /** 최근 run부터. 같은 시각이면 run id 코드 유닛 역순 — 감독·조회가 같은 run을 최신으로 본다. */
  static final String NEWEST_FIRST = " ORDER BY started_at DESC, run_id COLLATE \"C\" DESC";

  /** 소스의 마지막 스냅샷 완료(바인드 :source). */
  static final String SNAPSHOT_COMPLETION =
      "SELECT completed_at, deletion_count FROM source_snapshots WHERE source_instance_id = :source";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final BlobWriter blobs;

  public PostgresRunStore(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.blobs = new BlobWriter(jdbc);
  }

  @Override
  public CollectionRun start(RunId id, SourceInstanceId source, Instant startedAt) {
    // 죽은 프로세스가 남긴 running run: failed(stale_run)로 마감(ADR-0008 E-5). status='running' 조건부 한 문장 + 관측
    // 삭제 + INSERT를
    // 한 트랜잭션으로 — 그 사이 비성공 완료를 기록한 run을 덮어쓰지 않는다(리뷰 B-M-1).
    return tx.execute(
        status -> {
          List<String> stale =
              jdbc.sql(
                      """
                      UPDATE collection_runs SET status = 'failed', finished_at = :now, error_count = error_count + 1,
                        error_kind = 'stale_run', error_summary = 'superseded by a new run'
                      WHERE source_instance_id = :source AND status = 'running'
                      RETURNING run_id
                      """)
                  .param("now", Timestamp.from(startedAt))
                  .param("source", source.value())
                  .query(String.class)
                  .list();
          if (!stale.isEmpty())
            jdbc.sql("DELETE FROM run_observations WHERE run_id = ANY(:ids)")
                .param("ids", stale.toArray(String[]::new))
                .update();
          jdbc.sql(
                  "INSERT INTO collection_runs (run_id, source_instance_id, status, started_at) VALUES (:id, :source, 'running', :now)")
              .param("id", id.value())
              .param("source", source.value())
              .param("now", Timestamp.from(startedAt))
              .update();
          return get(id).orElseThrow();
        });
  }

  @Override
  public void requireAbsenceVerification(RunId run, String fingerprint) {
    int changed =
        jdbc.sql(
                "UPDATE collection_runs SET reconciliation_fingerprint = :fingerprint WHERE run_id = :run AND status = 'running' AND (reconciliation_fingerprint IS NULL OR reconciliation_fingerprint = :fingerprint)")
            .param("fingerprint", fingerprint)
            .param("run", run.value())
            .update();
    if (changed != 1) throw new RunStateException("Sync run is not running: " + run.value());
  }

  @Override
  public Optional<String> absenceRequirement(RunId run) {
    return jdbc.sql("SELECT reconciliation_fingerprint FROM collection_runs WHERE run_id = :run")
        .param("run", run.value())
        .query(String.class)
        .optional();
  }

  @Override
  public void recordAbsenceValidation(RunId run, AbsenceValidation validation) {
    int changed =
        jdbc.sql(
                """
        UPDATE collection_runs SET absence_candidate_token = :token, absence_exclusions = :exclusions
        WHERE run_id = :run AND status = 'running' AND reconciliation_fingerprint = :fingerprint
        """)
            .param("token", validation.candidateToken())
            .param("exclusions", validation.exclusions().toArray(String[]::new))
            .param("run", run.value())
            .param("fingerprint", validation.fingerprint())
            .update();
    if (changed != 1) throw new RunStateException("Absence validation does not match active run");
  }

  @Override
  public Optional<AbsenceValidation> absenceValidation(RunId run) {
    return jdbc.sql(
            "SELECT reconciliation_fingerprint, absence_candidate_token, absence_exclusions FROM collection_runs WHERE run_id = :run AND absence_candidate_token IS NOT NULL")
        .param("run", run.value())
        .query(
            (row, index) ->
                new AbsenceValidation(
                    row.getString("reconciliation_fingerprint"),
                    row.getString("absence_candidate_token"),
                    Set.of((String[]) row.getArray("absence_exclusions").getArray())))
        .optional();
  }

  @Override
  public Optional<CollectionRun> get(RunId id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM collection_runs WHERE run_id = :id")
        .param("id", id.value())
        .query(PostgresRunStore::map)
        .optional();
  }

  @Override
  public CollectionRun requireRunning(RunId id, SourceInstanceId source) {
    CollectionRun run =
        jdbc.sql("SELECT " + COLUMNS + " FROM collection_runs WHERE run_id = :id FOR UPDATE")
            .param("id", id.value())
            .query(PostgresRunStore::map)
            .optional()
            .orElseThrow(() -> new RunStateException("Sync run not found: " + id.value()));
    if (run.status() != RunStatus.RUNNING)
      throw new RunStateException("Sync run is not running: " + id.value());
    if (!run.source().equals(source))
      throw new RunStateException(
          "Sync run "
              + id.value()
              + " belongs to "
              + run.source().value()
              + ", not "
              + source.value());
    return run;
  }

  @Override
  public void addPage(RunId id, int upserted, int deleted, int unchanged, int rejected) {
    jdbc.sql(
            "UPDATE collection_runs SET pages_applied = pages_applied + 1, upserted = upserted + :u, deleted = deleted + :d, unchanged = unchanged + :n, rejected = rejected + :r WHERE run_id = :id")
        .param("u", upserted)
        .param("d", deleted)
        .param("n", unchanged)
        .param("r", rejected)
        .param("id", id.value())
        .update();
  }

  @Override
  public void observe(RunId id, Collection<String> externalIds) {
    for (String ext : new HashSet<>(externalIds))
      jdbc.sql(
              "INSERT INTO run_observations (run_id, external_id) VALUES (:id, :ext) ON CONFLICT DO NOTHING")
          .param("id", id.value())
          .param("ext", ext)
          .update();
  }

  @Override
  public Set<String> observations(RunId id) {
    return new HashSet<>(
        jdbc.sql("SELECT external_id FROM run_observations WHERE run_id = :id")
            .param("id", id.value())
            .query(String.class)
            .list());
  }

  @Override
  public CollectionRun finish(
      RunId id,
      RunOutcome outcome,
      CollectionCoverage coverage,
      Instant finishedAt,
      int reconciled) {
    jdbc.sql(
            """
            UPDATE collection_runs SET status = :status, coverage = :coverage, finished_at = :now, error_count = :errors,
              error_kind = :kind, error_summary = :summary, deleted = deleted + :reconciled WHERE run_id = :id
            """)
        .param("status", outcome.status().wire())
        .param("coverage", coverage.wire())
        .param("now", Timestamp.from(finishedAt))
        .param("errors", outcome.errorCount())
        .param("kind", outcome.errorKind().orElse(null))
        .param("summary", outcome.errorSummary().orElse(null))
        .param("reconciled", reconciled)
        .param("id", id.value())
        .update();
    jdbc.sql("DELETE FROM run_observations WHERE run_id = :id").param("id", id.value()).update();
    return get(id).orElseThrow();
  }

  @Override
  public void addRejections(
      RunId id, SourceInstanceId source, Instant capturedAt, List<RejectedItem> rejections) {
    for (RejectedItem r : rejections) {
      // 격리 콘텐츠의 blob도 같은 트랜잭션에 저장한다(리뷰 B-I-9)
      blobs.store(r.quarantined(), capturedAt);
      BlobReferences.translating(
          () ->
              ContentColumns.QUARANTINED
                  .bind(
                      jdbc.sql(
                              """
              INSERT INTO raw_rejections (run_id, source_instance_id, captured_at, observed_at, failure_kind, message, provider_locator,
                                          quarantined_storage, quarantined_media_type, quarantined_inline, quarantined_sha256, quarantined_size)
              VALUES (:id, :source, :captured, :observed, :kind, :message, :locator, :storage, :media, :inline, :sha, :size)
              """)
                          .param("id", id.value())
                          .param("source", source.value())
                          .param("captured", Timestamp.from(capturedAt))
                          .param("observed", Timestamp.from(r.observedAt()))
                          .param("kind", r.failureKind().wire())
                          .param("message", r.message())
                          .param("locator", r.providerLocatorJson().orElse(null)),
                      r.quarantined())
                  .update());
    }
  }

  @Override
  public List<RejectedItem> rejections(RunId id) {
    return jdbc.sql(
            "SELECT observed_at, failure_kind, message, provider_locator AS locator, quarantined_storage, quarantined_media_type, quarantined_inline, quarantined_sha256, quarantined_size FROM raw_rejections WHERE run_id = :id ORDER BY id")
        .param("id", id.value())
        .query(PostgresRunStore::rejection)
        .list();
  }

  @Override
  public Optional<CollectionRun> latest(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM collection_runs WHERE source_instance_id = :source"
                + NEWEST_FIRST
                + " LIMIT 1")
        .param("source", source.value())
        .query(PostgresRunStore::map)
        .optional();
  }

  @Override
  public Map<SourceInstanceId, Instant> lastSucceededAt(Set<SourceInstanceId> instances) {
    if (instances.isEmpty()) return Map.of();
    String[] successfulStatuses =
        Arrays.stream(RunStatus.values())
            .filter(RunStatus::isSuccess)
            .map(RunStatus::wire)
            .toArray(String[]::new);
    Map<SourceInstanceId, Instant> succeeded = new HashMap<>();
    jdbc.sql(
            """
            SELECT source_instance_id, MAX(finished_at) AS finished_at FROM collection_runs
            WHERE source_instance_id = ANY(:instances) AND status = ANY(:statuses)
              AND finished_at IS NOT NULL
            GROUP BY source_instance_id
            """)
        .param("instances", instances.stream().map(SourceInstanceId::value).toArray(String[]::new))
        .param("statuses", successfulStatuses)
        .query(
            (rs, row) ->
                Map.entry(
                    new SourceInstanceId(rs.getString("source_instance_id")),
                    rs.getTimestamp("finished_at").toInstant()))
        .list()
        .forEach(entry -> succeeded.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(succeeded);
  }

  @Override
  public int recentFailureCount(SourceInstanceId source) {
    // 집계는 Java(run 수가 작고 한 소스 범위)
    List<RunStatus> statuses =
        jdbc
            .sql(
                "SELECT status FROM collection_runs WHERE source_instance_id = :source"
                    + NEWEST_FIRST)
            .param("source", source.value())
            .query(String.class)
            .list()
            .stream()
            .map(RunStatus::fromWire)
            .toList();
    return new RunHistory(statuses).failuresSinceLastSuccess();
  }

  @Override
  public void recordSnapshotCompletion(
      SourceInstanceId source,
      Instant completedAt,
      int deletionCount,
      Optional<String> fingerprint) {
    jdbc.sql(
            "INSERT INTO source_snapshots (source_instance_id, completed_at, deletion_count, fingerprint) VALUES (:source, :at, :n, :fp) ON CONFLICT (source_instance_id) DO UPDATE SET completed_at = EXCLUDED.completed_at, deletion_count = EXCLUDED.deletion_count, fingerprint = EXCLUDED.fingerprint")
        .param("source", source.value())
        .param("at", Timestamp.from(completedAt))
        .param("n", deletionCount)
        .param("fp", fingerprint.orElse(null), Types.VARCHAR)
        .update();
  }

  @Override
  public boolean hasReconciliationBaseline(SourceInstanceId source, String fingerprint) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM source_snapshots WHERE source_instance_id = :source AND fingerprint = :fp)")
        .param("source", source.value())
        .param("fp", fingerprint)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source) {
    return jdbc.sql(SNAPSHOT_COMPLETION)
        .param("source", source.value())
        .query(PostgresRunStore::snapshotCompletion)
        .optional();
  }

  static SnapshotCompletion snapshotCompletion(ResultSet rs, int i) throws SQLException {
    return new SnapshotCompletion(
        rs.getTimestamp("completed_at").toInstant(), rs.getInt("deletion_count"));
  }

  /** raw_rejections 한 행(provider_locator는 별칭 locator). */
  static RejectedItem rejection(ResultSet rs, int i) throws SQLException {
    return new RejectedItem(
        RejectedItem.FailureKind.fromWire(rs.getString("failure_kind")),
        rs.getString("message"),
        Optional.ofNullable(rs.getString("locator")),
        rs.getTimestamp("observed_at").toInstant(),
        ContentColumns.QUARANTINED.read(rs));
  }

  static CollectionRun map(ResultSet rs, int i) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new CollectionRun(
        new RunId(rs.getString("run_id")),
        new SourceInstanceId(rs.getString("source_instance_id")),
        RunStatus.fromWire(rs.getString("status")),
        rs.getTimestamp("started_at").toInstant(),
        Optional.ofNullable(finished).map(Timestamp::toInstant),
        rs.getInt("pages_applied"),
        rs.getInt("upserted"),
        rs.getInt("deleted"),
        rs.getInt("unchanged"),
        rs.getInt("rejected"),
        rs.getInt("error_count"),
        Optional.ofNullable(rs.getString("error_kind")),
        Optional.ofNullable(rs.getString("error_summary")),
        Optional.ofNullable(rs.getString("coverage")).map(CollectionCoverage::fromWire));
  }
}
