package io.scos.collection.internal.postgres;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStateException;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.RunStore;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresRunStore implements RunStore {
  private final org.springframework.transaction.support.TransactionTemplate tx;
  static final String COLUMNS =
      "run_id, source_instance_id, status, started_at, finished_at, pages_applied, upserted, deleted, unchanged, rejected, error_count, error_kind, error_summary, coverage";
  private final JdbcClient jdbc;

  public PostgresRunStore(
      JdbcClient jdbc, org.springframework.transaction.support.TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
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
      RunStatus status,
      CollectionCoverage coverage,
      Instant finishedAt,
      int errorCount,
      Optional<String> errorKind,
      Optional<String> errorSummary,
      int reconciled) {
    jdbc.sql(
            """
            UPDATE collection_runs SET status = :status, coverage = :coverage, finished_at = :now, error_count = :errors,
              error_kind = :kind, error_summary = :summary, deleted = deleted + :reconciled WHERE run_id = :id
            """)
        .param("status", status.wire())
        .param("coverage", coverage.wire())
        .param("now", Timestamp.from(finishedAt))
        .param("errors", errorCount)
        .param("kind", errorKind.orElse(null))
        .param("summary", errorSummary.orElse(null))
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
      RawContent q = r.quarantined().orElse(null);
      if (q instanceof RawContent.Blob b && b.data().isPresent()) {
        // 격리 콘텐츠의 blob도 같은 트랜잭션에 저장한다(리뷰 B-I-9, FK가 참조를 강제한다)
        jdbc.sql(
                "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, :media, :size, :data, :now) ON CONFLICT (sha256) DO NOTHING")
            .param("sha", b.sha256())
            .param("media", b.mediaType())
            .param("size", b.size())
            .param("data", b.data().get())
            .param("now", Timestamp.from(capturedAt))
            .update();
      }
      BlobReferences.translating(
          () ->
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
                  .param("locator", r.providerLocatorJson().orElse(null))
                  .param(
                      "storage",
                      q == null ? null : q instanceof RawContent.Inline ? "inline" : "blob")
                  .param("media", q == null ? null : q.mediaType())
                  .param("inline", q instanceof RawContent.Inline i ? i.valueJson() : null)
                  .param("sha", q instanceof RawContent.Blob b ? b.sha256() : null)
                  .param("size", q instanceof RawContent.Blob b ? b.size() : null)
                  .update());
    }
  }

  @Override
  public List<RejectedItem> rejections(RunId id) {
    return jdbc.sql(
            "SELECT observed_at, failure_kind, message, provider_locator AS locator, quarantined_storage, quarantined_media_type, quarantined_inline AS qinline, quarantined_sha256, quarantined_size FROM raw_rejections WHERE run_id = :id ORDER BY id")
        .param("id", id.value())
        .query(PostgresRunStore::rejection)
        .list();
  }

  @Override
  public Optional<CollectionRun> latest(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM collection_runs WHERE source_instance_id = :source ORDER BY started_at DESC, run_id DESC LIMIT 1")
        .param("source", source.value())
        .query(PostgresRunStore::map)
        .optional();
  }

  @Override
  public int recentFailureCount(SourceInstanceId source) {
    // 최근 run부터 거슬러 올라가 첫 성공에서 멈춘다 — 집계는 Java(run 수가 작고 한 소스 범위)
    List<String> statuses =
        jdbc.sql(
                "SELECT status FROM collection_runs WHERE source_instance_id = :source ORDER BY started_at DESC, run_id DESC")
            .param("source", source.value())
            .query(String.class)
            .list();
    int count = 0;
    for (String status : statuses) {
      if (status.equals("succeeded") || status.equals("succeeded_with_rejections")) break;
      if (status.equals("failed") || status.equals("partially_succeeded")) count++;
    }
    return count;
  }

  @Override
  public void recordSnapshotCompletion(
      SourceInstanceId source, Instant completedAt, int deletionCount) {
    jdbc.sql(
            "INSERT INTO source_snapshots (source_instance_id, completed_at, deletion_count) VALUES (:source, :at, :n) ON CONFLICT (source_instance_id) DO UPDATE SET completed_at = EXCLUDED.completed_at, deletion_count = EXCLUDED.deletion_count")
        .param("source", source.value())
        .param("at", Timestamp.from(completedAt))
        .param("n", deletionCount)
        .update();
  }

  @Override
  public Optional<SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT completed_at, deletion_count FROM source_snapshots WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(
            (rs, i) ->
                new SnapshotCompletion(
                    rs.getTimestamp("completed_at").toInstant(), rs.getInt("deletion_count")))
        .optional();
  }

  /** raw_rejections 한 행(열 별칭 locator·qinline). */
  static RejectedItem rejection(ResultSet rs, int i) throws SQLException {
    String storage = rs.getString("quarantined_storage");
    Optional<RawContent> q =
        storage == null
            ? Optional.empty()
            : storage.equals("inline")
                ? Optional.of(
                    new RawContent.Inline(
                        rs.getString("quarantined_media_type"), rs.getString("qinline")))
                : Optional.of(
                    new RawContent.Blob(
                        rs.getString("quarantined_media_type"),
                        rs.getString("quarantined_sha256"),
                        rs.getLong("quarantined_size"),
                        Optional.empty()));
    return new RejectedItem(
        RejectedItem.FailureKind.fromWire(rs.getString("failure_kind")),
        rs.getString("message"),
        Optional.ofNullable(rs.getString("locator")),
        rs.getTimestamp("observed_at").toInstant(),
        q);
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
