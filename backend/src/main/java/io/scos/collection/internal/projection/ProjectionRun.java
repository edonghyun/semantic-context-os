package io.scos.collection.internal.projection;

import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.LeaseHeartbeat;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.engine.support.projection.SourceSync;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.SpaceId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 투영 실행(TS projection-run.ts + raw-catalog-connector.ts, G1·ADR-0007·ADR-0018 §6): Outbox의 due 소스마다
 * active 원본 전량(500개 페이지) → 투영기(kind별) → documentId 정렬 순 투영 → SourceSync.sync(prune) → 성공이면 요청 완료,
 * 실패(문서 실패 포함)면 attempts·backoff. 문서 실패는 documentRef = sha256(uri)로만 보고한다(원문·URL 비노출). 알 수 없는 kind는
 * skipped(요청 완료 — 투영할 것이 없다).
 */
public final class ProjectionRun {
  public enum Outcome {
    SUCCEEDED,
    FAILED,
    SKIPPED_UNKNOWN_KIND,
    /** 같은 소스의 투영이 진행 중(점유 live) — 요청은 그대로 남는다. */
    SKIPPED_RUNNING,
    /** 처리 중 다른 투영이 점유를 가져갔다 — 그쪽이 요청을 끝낸다. 실패가 아니다. */
    SUPERSEDED,
    /** 종료(취소)로 중단됐다 — 요청은 그대로 남고 실패로 세지 않는다(attempts·backoff 없음). 다음 프로세스가 다시 투영한다. */
    CANCELLED;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record DocumentFailure(String documentRef, String stage, String reason) {}

  public record Report(
      SourceInstanceId source,
      String kind,
      Outcome outcome,
      int documents,
      int units,
      int deleted,
      int projectionSkipped,
      Optional<String> error,
      List<DocumentFailure> documentFailures) {}

  private static final Logger log = LoggerFactory.getLogger(ProjectionRun.class);
  static final int PAGE_SIZE = 500;

  private final OutboxPort outbox;
  private final SourceInstanceStore sources;
  private final RawItemStore rawItems;
  private final Projectors projectors;
  private final SourceSync ingestor;
  private final ProjectionSpaces spaces;
  private final ProjectionRetryPolicy retry;
  private final WorkClaimPort claims;
  private final OwnerId owner;
  private final LeaseHeartbeats heartbeats;
  private final Clock clock;
  private final ProjectionMetrics metrics;
  private final Progress progress;

  public ProjectionRun(
      OutboxPort outbox,
      SourceInstanceStore sources,
      RawItemStore rawItems,
      Projectors projectors,
      SourceSync ingestor,
      ProjectionSpaces spaces,
      ProjectionRetryPolicy retry,
      WorkClaimPort claims,
      OwnerId owner,
      LeaseHeartbeats heartbeats,
      Clock clock,
      ProjectionMetrics metrics,
      Progress progress) {
    this.progress = progress;
    this.metrics = metrics;
    this.outbox = outbox;
    this.sources = sources;
    this.rawItems = rawItems;
    this.projectors = projectors;
    this.ingestor = ingestor;
    this.spaces = spaces;
    this.retry = retry;
    this.claims = claims;
    this.owner = owner;
    this.heartbeats = heartbeats;
    this.clock = clock;
  }

  /**
   * due 요청 전부를 한 번씩 시도한다(사이클당 소스당 1회). 취소되면 남은 소스는 시작하지 않고, 진행 중이던 소스는 실패 기록 없이 CANCELLED로
   * 끝난다(ADR-0020 §1 — 공급자 대기는 호출 스레드 인터럽트로 깨어난다).
   */
  public List<Report> runDue(CancellationToken cancel) {
    List<Report> reports = new ArrayList<>();
    for (SourceInstanceId id : outbox.due(clock.instant())) {
      if (cancel.isCancelled()) break;
      reports.add(runOne(id, cancel));
    }
    return reports;
  }

  private Report runOne(SourceInstanceId id, CancellationToken cancel) {
    long startedNanos = System.nanoTime();
    Report report = runOneUnmetered(id, cancel);
    metrics.run(report.outcome()).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    metrics.documents(report.documents(), report.documentFailures());
    return report;
  }

  private Report runOneUnmetered(SourceInstanceId id, CancellationToken cancel) {
    // 처리 시작 시점의 요청 세대 — 완료·실패 기록은 이 세대일 때만 적용된다(처리 중 들어온 요청은 남는다, 리뷰 B-C-1)
    long seen = outbox.request(id).map(OutboxPort.Request::version).orElse(0L);
    Optional<SourceInstance> instance = sources.get(id);
    if (instance.isEmpty()) {
      failed(id, seen, "source instance not found");
      return new Report(
          id, "?", Outcome.FAILED, 0, 0, 0, 0, Optional.of("source instance not found"), List.of());
    }
    String kind = instance.get().kind();
    Optional<Projector> found = projectors.forKind(kind);
    if (found.isEmpty()) {
      outbox.complete(id, seen);
      return new Report(
          id, kind, Outcome.SKIPPED_UNKNOWN_KIND, 0, 0, 0, 0, Optional.empty(), List.of());
    }
    Projector projector = found.get();
    // 소스별 투영 점유(리뷰 B-I-6): 겹친 투영이 옛 내용으로 되돌리지 못하게 쓰기 트랜잭션마다 저장 경계에서 token을 검사한다
    Optional<WorkClaim> claimed =
        claims.tryClaim(projectionKey(id), owner, heartbeats.ttl(), clock.instant());
    if (claimed.isEmpty())
      return new Report(id, kind, Outcome.SKIPPED_RUNNING, 0, 0, 0, 0, Optional.empty(), List.of());
    WorkClaim claim = claimed.get();
    // 수집과 같은 heartbeat(TTL/2) — TTL보다 긴 투영도 끝까지 간다. 쓰기 트랜잭션마다 heartbeat 실패·점유 검사를 함께 본다.
    LeaseHeartbeat heartbeat = heartbeats.begin(claim);
    Runnable guard =
        () -> {
          heartbeat.assertHealthy();
          claims.requireActive(claim, clock.instant());
          progress.mark(); // 문서(또는 정리) 쓰기 하나 — 긴 투영의 진행 신호
        };
    try {
      // 점유 뒤의 어떤 실패도(heartbeat 등록 거부 포함) finally의 해제를 거친다 — 점유가 TTL까지 남지 않는다
      heartbeat.start();
      SpaceId space = spaces.resolve(instance.get());
      Map<String, List<RawItem>> groups = projector.groupIntoDocuments(readAllActive(id));
      List<SourceDocument> documents = new ArrayList<>();
      Set<String> live = new HashSet<>();
      int skipped = 0;
      for (var e : groups.entrySet()) { // TreeMap: documentId 정렬 순
        Optional<SourceDocument> doc =
            projector.project(id.value(), e.getKey(), e.getValue(), this::readContent);
        if (doc.isEmpty()) {
          skipped++;
          continue;
        }
        documents.add(doc.get());
        live.add(e.getKey());
      }
      SyncReport result = ingestor.sync(space, id.value(), documents, live, guard);
      metrics.embeddings(result.embeddings());
      List<DocumentFailure> failures =
          result.errors().stream()
              .map(d -> new DocumentFailure(Text.sha256Hex(d.uri()), d.stage(), reason(d.cause())))
              .toList();
      if (!failures.isEmpty() && cancel.isCancelled()) return cancelled(id, kind);
      if (failures.isEmpty()) {
        outbox.complete(id, seen);
        return new Report(
            id,
            kind,
            Outcome.SUCCEEDED,
            result.documents(),
            result.units(),
            result.deleted(),
            skipped,
            Optional.empty(),
            List.of());
      }
      String error = failures.size() + " document ingestion(s) failed";
      failed(id, seen, error);
      return new Report(
          id,
          kind,
          Outcome.FAILED,
          result.documents(),
          result.units(),
          result.deleted(),
          skipped,
          Optional.of(error),
          failures);
    } catch (ClaimLostException e) {
      if (cancel.isCancelled()) return cancelled(id, kind);
      // 경쟁자가 점유를 가져갔으면 그쪽이 요청을 끝낸다(SUPERSEDED, 실패 아님). 경쟁자 없이 만료만 됐으면(TTL보다 긴 투영) 실패로 세어
      // attempts·parked 상한을 받는다 — 무한 재시도 없음(불변식 7).
      Optional<WorkClaim> current = claims.current(projectionKey(id));
      // 내 token 뒤에 다른 점유가 있었으면(끝났더라도) 경쟁자가 요청을 처리한 것이다
      boolean superseded = current.map(c -> c.fencingToken() != claim.fencingToken()).orElse(false);
      if (superseded)
        return new Report(
            id, kind, Outcome.SUPERSEDED, 0, 0, 0, 0, Optional.of(e.getMessage()), List.of());
      failed(id, seen, "claim_lost: " + e.getMessage());
      return new Report(
          id,
          kind,
          Outcome.FAILED,
          0,
          0,
          0,
          0,
          Optional.of("claim_lost: " + e.getMessage()),
          List.of());
    } catch (RuntimeException e) {
      if (cancel.isCancelled()) return cancelled(id, kind);
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      failed(id, seen, message);
      return new Report(id, kind, Outcome.FAILED, 0, 0, 0, 0, Optional.of(message), List.of());
    } finally {
      heartbeat.stop();
      claims.release(claim, clock.instant());
    }
  }

  /** 종료로 중단: 요청(version 포함)을 건드리지 않는다 — 실패가 아니므로 attempts·parked 예산을 쓰지 않는다. */
  private static Report cancelled(SourceInstanceId id, String kind) {
    return new Report(id, kind, Outcome.CANCELLED, 0, 0, 0, 0, Optional.of("cancelled"), List.of());
  }

  /**
   * 실패 기록 + 운영 신호: version과 무관한 연속 실패 수가 maxAttempts에 이르면 경고한다 — 새 데이터가 계속 오는 소스는 attempts가 리셋돼
   * parked에 이르지 않으므로(불변식 7의 예산은 데이터 단위) 이 수가 결정적 실패를 드러낸다(ADR-0018 §11).
   */
  private void failed(SourceInstanceId id, long seen, String error) {
    if (!outbox.recordFailure(id, seen, clock.instant(), error, retry)) return;
    outbox
        .request(id)
        .filter(r -> r.consecutiveFailures() >= retry.maxAttempts())
        .ifPresent(
            r ->
                log.warn(
                    "projection of {} failed {} times in a row (attempts {}, parked {}): {}",
                    id.value(),
                    r.consecutiveFailures(),
                    r.attempts(),
                    r.parked(),
                    error));
  }

  /** 수집 점유와 다른 키(같은 work_claims 테이블). */
  static SourceInstanceId projectionKey(SourceInstanceId source) {
    return new SourceInstanceId("projection:" + source.value());
  }

  private List<RawItem> readAllActive(SourceInstanceId id) {
    List<RawItem> items = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page = rawItems.listActive(id, PAGE_SIZE, cursor);
      items.addAll(page.items());
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return items;
  }

  /** inline은 그대로, blob은 저장소에서 읽는다. 참조가 있는데 blob이 없으면 읽기 오류(F9: 자동 복구 없음). */
  private Optional<String> readContent(RawItem item) {
    return item.content()
        .map(
            c ->
                switch (c) {
                  case RawContent.Inline i -> i.valueJson();
                  case RawContent.Blob b ->
                      new String(
                          rawItems
                              .blob(b.sha256())
                              .orElseThrow(
                                  () -> new IllegalStateException("missing blob " + b.sha256())),
                          StandardCharsets.UTF_8);
                });
  }

  /** 예외 타입·필드로 분류한다(DESIGN-PATTERNS §13: 메시지 정규식 금지). */
  static String reason(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof Embedder.EmbedderException e && e.status().isPresent()) {
        int status = e.status().getAsInt();
        if (status == 429) return "rate_limited";
        if (status / 100 == 5) return "provider_unavailable";
      }
      if (t instanceof java.net.http.HttpTimeoutException
          || t instanceof java.net.SocketTimeoutException
          || t instanceof java.util.concurrent.TimeoutException) return "timeout";
      if (t instanceof DataAccessException) return "storage";
    }
    return "unclassified";
  }
}
