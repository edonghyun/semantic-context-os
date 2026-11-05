package io.scos.collection.internal.projection;

import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.LeaseHeartbeat;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.RawAclStore;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.engine.EventLog;
import io.scos.engine.support.projection.SourceSync;
import io.scos.engine.support.projection.SyncReport;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.engine.support.text.Text;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 투영 실행(G1·ADR-0007·ADR-0018 §6): Outbox의 due 소스마다 active 원본 전량(500개 페이지) → 투영기(kind별) → documentId
 * 정렬 순 투영 → SourceSync.sync(prune) → 성공이면 요청 완료, 실패(문서 실패 포함)면 attempts·backoff. 문서 실패는 documentRef
 * = sha256(uri, 렌더 실패는 documentId·그룹 실패는 sourceId)로만 보고한다(원문·URL 비노출). 알 수 없는 kind는 skipped(요청 완료 —
 * 투영할 것이 없다).
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
      int ownershipSkipped,
      Optional<String> error,
      List<DocumentFailure> documentFailures) {
    public Report {
      documentFailures = List.copyOf(documentFailures);
    }

    /** 투영까지 가지 못했다(셈은 모두 0). */
    static Report without(
        SourceInstanceId source, String kind, Outcome outcome, Optional<String> error) {
      return new Report(source, kind, outcome, 0, 0, 0, 0, 0, error, List.of());
    }

    static Report failed(SourceInstanceId source, String kind, String error) {
      return without(source, kind, Outcome.FAILED, Optional.of(error));
    }

    static Report cancelled(SourceInstanceId source, String kind) {
      return without(source, kind, Outcome.CANCELLED, Optional.of("cancelled"));
    }

    /** 동기화까지 마쳤다. 문서 실패가 있으면 failed와 그 실패, 없으면 succeeded. */
    static Report synced(
        SourceInstanceId source,
        String kind,
        SyncReport result,
        int skipped,
        Optional<String> error,
        List<DocumentFailure> failures) {
      return new Report(
          source,
          kind,
          failures.isEmpty() ? Outcome.SUCCEEDED : Outcome.FAILED,
          result.documents(),
          result.units(),
          result.deleted(),
          skipped,
          result.ownershipSkipped().size(),
          error,
          failures);
    }
  }

  /** 투영기가 낸 문서(documentId 정렬 순), 살아 있는 documentId, 문서가 되지 않은 묶음 수. */
  private record ProjectedDocuments(
      List<SourceDocument> documents,
      Set<String> live,
      int skipped,
      Map<String, DocumentAcl> acls,
      List<DocumentFailure> failures,
      Map<String, List<RawItem>> groups) {}

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
  private final SourceAdapters adapters;
  private final boolean collectAccess;
  private final DocumentOwnership ownership;
  private final RawAclStore rawAcls;
  private final AccessCollection.Options accessOptions;
  private final TransactionOperations tx;

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
      Progress progress,
      SourceAdapters adapters,
      boolean collectAccess,
      DocumentOwnership ownership,
      RawAclStore rawAcls,
      TransactionOperations tx,
      AccessCollection.Options accessOptions) {
    this.rawAcls = rawAcls;
    this.accessOptions = accessOptions;
    this.tx = tx;
    this.ownership = ownership;
    this.adapters = adapters;
    this.collectAccess = collectAccess;
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
      return Report.failed(id, "?", "source instance not found");
    }
    String kind = instance.get().kind();
    Optional<Projector> found = projectors.forKind(kind);
    if (found.isEmpty()) {
      outbox.complete(id, seen);
      return Report.without(id, kind, Outcome.SKIPPED_UNKNOWN_KIND, Optional.empty());
    }
    // 소스별 투영 점유(리뷰 B-I-6): 겹친 투영이 옛 내용으로 되돌리지 못하게 쓰기 트랜잭션마다 저장 경계에서 token을 검사한다
    Optional<WorkClaim> claimed =
        claims.tryClaim(projectionKey(id), owner, heartbeats.ttl(), clock.instant());
    if (claimed.isEmpty())
      return Report.without(id, kind, Outcome.SKIPPED_RUNNING, Optional.empty());
    WorkClaim claim = claimed.get();
    LeaseHeartbeat heartbeat = heartbeats.begin(claim);
    try {
      // 점유 뒤의 어떤 실패도(heartbeat 등록 거부 포함) finally의 해제를 거친다 — 점유가 TTL까지 남지 않는다
      heartbeat.start();
      return project(instance.get(), found.get(), seen, guard(heartbeat, claim, cancel), cancel);
    } catch (ClaimLostException e) {
      if (cancel.isCancelled()) return Report.cancelled(id, kind);
      // 경쟁자가 점유를 가져갔으면 그쪽이 요청을 끝낸다(SUPERSEDED, 실패 아님). 경쟁자 없이 만료만 됐으면(TTL보다 긴 투영) 실패로 세어
      // attempts·parked 상한을 받는다 — 무한 재시도 없음(불변식 7).
      // ClaimLostException은 늘 메시지를 싣는다(점유 어댑터)
      if (supersededAfter(claim))
        return Report.without(
            id, kind, Outcome.SUPERSEDED, Optional.of(Objects.requireNonNull(e.getMessage())));
      failed(id, seen, "claim_lost: " + e.getMessage());
      return Report.failed(id, kind, "claim_lost: " + e.getMessage());
    } catch (RuntimeException e) {
      if (cancel.isCancelled()) return Report.cancelled(id, kind);
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      failed(id, seen, message);
      return Report.failed(id, kind, message);
    } finally {
      heartbeat.stop();
      claims.release(claim, clock.instant());
    }
  }

  /**
   * 수집과 같은 heartbeat(TTL/2) — TTL보다 긴 투영도 끝까지 간다. 쓰기 트랜잭션마다 heartbeat 실패·점유 검사를 함께 보고, 쓰기 하나를 긴 투영의
   * 진행 신호로 남긴다.
   */
  private Runnable guard(LeaseHeartbeat heartbeat, WorkClaim claim, CancellationToken cancel) {
    return () -> {
      if (cancel.isCancelled()) throw new WriteGuardException("Projection cancelled");
      heartbeat.assertHealthy();
      claims.requireActive(claim, clock.instant());
      progress.mark();
    };
  }

  /** 점유를 잃은 뒤: 내 token 뒤에 다른 점유가 있었으면(끝났더라도) 경쟁자가 요청을 처리한 것이다. */
  private boolean supersededAfter(WorkClaim claim) {
    return claims
        .current(claim.source())
        .map(c -> c.fencingToken() != claim.fencingToken())
        .orElse(false);
  }

  /** active 원본 전량을 문서로 투영해 동기화하고, 문서 실패가 없으면 요청을 끝낸다. */
  private Report project(
      SourceInstance instance,
      Projector projector,
      long seen,
      Runnable guard,
      CancellationToken cancel) {
    SourceInstanceId id = instance.id();
    String kind = instance.kind();
    SpaceId space = spaces.resolve(instance);
    ProjectedDocuments projected = projectDocuments(instance, projector, space);
    SyncReport result =
        ingestor.sync(
            space, id.value(), projected.documents(), projected.live(), projected.acls(), guard);
    reportOwnershipSkipped(instance.id(), result.ownershipSkipped(), guard);
    restrictForeignCarriers(instance, projected.groups(), result.ownershipSkipped(), guard);
    metrics.embeddings(result.embeddings());
    List<DocumentFailure> failures = new ArrayList<>(projected.failures());
    result.errors().stream()
        .map(d -> new DocumentFailure(Text.sha256Hex(d.uri()), d.stage(), reason(d.cause())))
        .forEach(failures::add);
    if (!failures.isEmpty() && cancel.isCancelled()) return Report.cancelled(id, kind);
    if (failures.isEmpty()) {
      outbox.complete(id, seen);
      return Report.synced(id, kind, result, projected.skipped(), Optional.empty(), failures);
    }
    String error = failures.size() + " document ingestion(s) failed";
    failed(id, seen, error);
    return Report.synced(id, kind, result, projected.skipped(), Optional.of(error), failures);
  }

  private void reportOwnershipSkipped(
      SourceInstanceId source, Set<String> skipped, Runnable guard) {
    tx.executeWithoutResult(
        status -> {
          guard.run();
          sources.recordOwnershipSkipped(source, skipped.size());
        });
    if (!skipped.isEmpty())
      EventLog.warn(log, "projection.document.ownership_skipped")
          .source(source)
          .detail(
              "count",
              skipped.size(),
              "documentRefs",
              skipped.stream().sorted().limit(20).map(Text::sha256Hex).toList())
          .log();
  }

  private ProjectedDocuments projectDocuments(
      SourceInstance instance, Projector projector, SpaceId space) {
    Instant decidedAt = clock.instant();
    List<RawItem> items = List.copyOf(readAllActive(instance.id()));
    Map<String, List<RawItem>> groups;
    Set<String> invalid;
    try {
      groups = new TreeMap<>(projector.groupIntoDocuments(items));
      invalid =
          collectAccess
              ? adapters.require(instance.kind()).accessPolicy().invalidDocuments(items, groups)
              : Set.of();
      invalid.forEach(id -> groups.putIfAbsent(id, List.of()));
    } catch (RuntimeException e) {
      return groupingFailure(instance, space, decidedAt, e);
    }
    Map<String, DocumentAcl> acls = calculateAcls(instance, space, groups, decidedAt);
    return renderDocuments(instance.id(), projector, groups, invalid, acls, decidedAt);
  }

  private Map<String, DocumentAcl> calculateAcls(
      SourceInstance instance,
      SpaceId space,
      Map<String, List<RawItem>> groups,
      Instant decidedAt) {
    Map<String, DocumentAcl> acls = new HashMap<>();
    if (collectAccess) {
      var adapter = adapters.require(instance.kind());
      var calculator = new DocumentAcls(accessOptions);
      groups.forEach(
          (id, items) ->
              acls.put(
                  id, calculator.calculate(instance.descriptor(), adapter, id, items, decidedAt)));
      ownership.listDocumentIds(space, instance.id().value()).stream()
          .filter(id -> !groups.containsKey(id))
          .forEach(
              id -> acls.put(id, failedAcl(id, decidedAt, AccessFailureReason.DOCUMENT_MISSING)));
    }
    return acls;
  }

  private ProjectedDocuments renderDocuments(
      SourceInstanceId source,
      Projector projector,
      Map<String, List<RawItem>> groups,
      Set<String> invalid,
      Map<String, DocumentAcl> acls,
      Instant decidedAt) {
    List<SourceDocument> documents = new ArrayList<>();
    Set<String> live = new HashSet<>();
    List<DocumentFailure> failures = new ArrayList<>();
    int skipped = 0;
    for (var e : groups.entrySet()) {
      String id = e.getKey();
      try {
        if (invalid.contains(id))
          throw new IllegalArgumentException("Incomplete document access carriers");
        Optional<SourceDocument> doc =
            projector.project(source.value(), id, e.getValue(), this::readContent);
        if (doc.isEmpty()) {
          skipped++;
          if (collectAccess)
            acls.put(id, failedAcl(id, decidedAt, AccessFailureReason.RENDER_FAILED));
          continue;
        }
        if (!doc.get().documentId().equals(id) || !doc.get().sourceId().equals(source.value()))
          throw new IllegalArgumentException("Projected document identity does not match bundle");
        documents.add(doc.get());
        live.add(id);
      } catch (RuntimeException ex) {
        live.add(id);
        if (collectAccess)
          acls.put(
              id,
              failedAcl(
                  id,
                  decidedAt,
                  invalid.contains(id)
                      ? AccessFailureReason.INVALID_CARRIER
                      : AccessFailureReason.RENDER_FAILED));
        failures.add(new DocumentFailure(Text.sha256Hex(id), "project", reason(ex)));
      }
    }
    return new ProjectedDocuments(
        documents, live, skipped, Map.copyOf(acls), List.copyOf(failures), groups);
  }

  private ProjectedDocuments groupingFailure(
      SourceInstance instance, SpaceId space, Instant decidedAt, RuntimeException error) {
    Set<String> live = ownership.listDocumentIds(space, instance.id().value());
    Map<String, DocumentAcl> acls = new HashMap<>();
    if (collectAccess)
      live.forEach(
          id -> acls.put(id, failedAcl(id, decidedAt, AccessFailureReason.GROUPING_FAILED)));
    var failure =
        new DocumentFailure(Text.sha256Hex(instance.id().value()), "project", reason(error));
    return new ProjectedDocuments(List.of(), live, 0, Map.copyOf(acls), List.of(failure), Map.of());
  }

  /** 상대 소유 문서의 운반 원본만 차단한다. 변경과 후속 요청은 같은 투영 점유 트랜잭션이다. */
  private void restrictForeignCarriers(
      SourceInstance instance,
      Map<String, List<RawItem>> groups,
      Set<String> skipped,
      Runnable guard) {
    if (!collectAccess || skipped.isEmpty()) return;
    var policy = adapters.require(instance.kind()).accessPolicy();
    tx.executeWithoutResult(
        s -> {
          guard.run();
          boolean changed = false;
          for (String id : skipped) {
            for (RawItem item : groups.getOrDefault(id, List.of())) {
              if (!policy.carries(item)) continue;
              Instant observedAt =
                  item.acl() instanceof RawAcl.Observed acl ? acl.observedAt() : clock.instant();
              changed |=
                  rawAcls.observe(
                      instance.id(),
                      item.externalId(),
                      RawAcl.failed(AccessFailureReason.OWNERSHIP_CONFLICT, observedAt),
                      observedAt);
            }
          }
          if (changed) outbox.requestProjection(instance.id(), clock.instant());
        });
  }

  private static DocumentAcl failedAcl(
      String documentId, Instant decidedAt, AccessFailureReason reason) {
    return new DocumentAcl(documentId, DocumentAcl.Status.FAILED, Set.of(), decidedAt, reason);
  }

  /**
   * 실패 기록 + 운영 신호: version과 무관한 연속 실패 수가 maxAttempts에 이르면 error 이벤트를 남긴다 — 새 데이터가 계속 오는 소스는
   * attempts가 리셋돼 parked에 이르지 않으므로(불변식 7의 예산은 데이터 단위) 이 수가 결정적 실패를 드러낸다(ADR-0018 §11). 스스로 회복하지 못한
   * 실패라 사람이 봐야 한다(logging.md §3).
   */
  private void failed(SourceInstanceId id, long seen, String error) {
    if (!outbox.recordFailure(id, seen, clock.instant(), error, retry)) return;
    outbox
        .request(id)
        .filter(r -> r.consecutiveFailures() >= retry.maxAttempts())
        .ifPresent(
            r ->
                EventLog.error(log, "projection.request.failed_repeatedly")
                    .source(id)
                    .detail(
                        "consecutiveFailures", r.consecutiveFailures(),
                        "attempts", r.attempts(),
                        "parked", r.parked(),
                        "errorSummary", error)
                    .log());
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
      if (t instanceof HttpTimeoutException
          || t instanceof SocketTimeoutException
          || t instanceof TimeoutException) return "timeout";
      if (t instanceof DataAccessException) return "storage";
    }
    return "unclassified";
  }
}
