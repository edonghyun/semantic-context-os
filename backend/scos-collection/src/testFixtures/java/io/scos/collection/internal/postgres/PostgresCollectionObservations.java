package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.WorkClaim;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 소스 하나의 수집 결과를 PostgreSQL에서 읽는 관찰(커넥터 종류와 무관, DB 종단 시나리오·kill 프로브 공용): 원본·tombstone·체크포인트·투영
 * 요청·점유·run 기록. 업무 질문에 답하고 단언하지 않는다(TESTING §3.5). 시각 판단(점유가 살아 있는가)은 넘겨받은 시계로 한다.
 */
public final class PostgresCollectionObservations {
  /** active 원본을 읽는 페이지 크기. */
  private static final int ACTIVE_PAGE = 100;

  private final DataSource dataSource;
  private final PostgresCollectionConfiguration.Adapters adapters;
  private final SourceInstanceId source;
  private final Clock clock;

  public PostgresCollectionObservations(
      DataSource dataSource, SourceInstanceId source, Clock clock) {
    this.dataSource = dataSource;
    this.adapters = PostgresCollectionConfiguration.adapters(dataSource);
    this.source = source;
    this.clock = clock;
  }

  // ---- 원본·체크포인트·투영 요청 ----

  public long rawItems() {
    return adapters.rawItems().count(source);
  }

  public Optional<RawRecord> rawItem(String externalId) {
    return adapters.rawItems().get(source, externalId);
  }

  /** active 원본의 외부 id → sourceVersion(저장소가 주는 순서). 커넥터 DB 종단·kill 테스트가 TS 원본과 비교한다. */
  public Map<String, String> activeVersions() {
    Map<String, String> out = new LinkedHashMap<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page = adapters.rawItems().listActive(source, ACTIVE_PAGE, cursor);
      page.items().forEach(item -> out.put(item.externalId(), item.sourceVersion()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 이 sha256 blob의 저장된 바이트 수(없으면 empty). */
  public Optional<Long> blobSize(String sha256) {
    return adapters.rawItems().blob(sha256).map(bytes -> (long) bytes.length);
  }

  public long tombstones() {
    return adapters.rawItems().tombstoneCount(source);
  }

  public long checkpointRevision() {
    return adapters.checkpoints().read(source).revision();
  }

  /** 저장된 체크포인트(없으면 empty). */
  public Optional<Checkpoint> checkpoint() {
    return adapters.checkpoints().read(source).checkpoint();
  }

  public boolean projectionRequested() {
    return adapters.outbox().pending().contains(source);
  }

  /** 모든 소스의 투영 요청 수. */
  public int pendingProjections() {
    return adapters.outbox().pending().size();
  }

  // ---- 점유 ----

  /** 점유 행이 있다(만료와 무관). */
  public boolean hasClaim() {
    return adapters.claims().current(source).isPresent();
  }

  /** 살아 있는 점유가 있다(없으면 false). */
  public boolean hasActiveClaim() {
    return adapters.claims().current(source).filter(c -> c.isActiveAt(clock.instant())).isPresent();
  }

  /** 점유가 살아 있다 — 점유가 있어야 하는 상황용(없으면 전제 실패로 던진다, hasActiveClaim과의 차이). */
  public boolean holdsActiveClaim() {
    return claimRow().isActiveAt(clock.instant());
  }

  /** 점유가 없거나 만료됐다 — 죽은 worker 뒤 재개 조건. */
  public boolean leaseExpired() {
    return adapters.claims().current(source).map(c -> !c.isActiveAt(clock.instant())).orElse(true);
  }

  public Instant claimExpiresAt() {
    return claimRow().expiresAt();
  }

  private WorkClaim claimRow() {
    return adapters
        .claims()
        .current(source)
        .orElseThrow(
            () -> new IllegalStateException("given 준비 실패: 소스 " + source.value() + "의 점유 행이 없다"));
  }

  // ---- run ----

  public Optional<RunId> latestRunId() {
    return adapters.runs().latest(source).map(CollectionRun::id);
  }

  public CollectionRun run(RunId id) {
    return adapters
        .runs()
        .get(id)
        .orElseThrow(() -> new IllegalStateException("given 준비 실패: run " + id.value() + "이 없다"));
  }

  public Optional<RunStore.SnapshotCompletion> lastSnapshotCompletion() {
    return adapters.runs().lastSnapshotCompletion(source);
  }

  /** 소스 run의 "상태/오류 종류"(시작 순, 같은 시각이면 run id 순). */
  public List<String> runStatuses() {
    return new JdbcTemplate(dataSource)
        .queryForList(
            "SELECT status || '/' || coalesce(error_kind, '-') FROM collection_runs WHERE source_instance_id = ? ORDER BY started_at, run_id",
            String.class,
            source.value());
  }

  /** run의 "상태/오류 종류", 없으면 "none". */
  public String runStatus(RunId id) {
    return adapters
        .runs()
        .get(id)
        .map(r -> r.status().wire() + "/" + r.errorKind().orElse("-"))
        .orElse("none");
  }

  /**
   * kill 프로브의 결과 줄(ChildJvm 결과 프로토콜): {@code RESULT <phase> status= pages= upserted= count=
   * revision= deadRun= pending=}. 커넥터마다 같은 형식이라 부모 테스트가 같은 키로 읽는다.
   */
  public String probeResult(String phase, CollectionRuntime.Report report, RunId deadRun) {
    return "RESULT "
        + phase
        + " status="
        + report.status().wire()
        + " pages="
        + report.pagesApplied()
        + " upserted="
        + report.upserted()
        + " count="
        + rawItems()
        + " revision="
        + checkpointRevision()
        + " deadRun="
        + runStatus(deadRun)
        + " pending="
        + pendingProjections();
  }
}
