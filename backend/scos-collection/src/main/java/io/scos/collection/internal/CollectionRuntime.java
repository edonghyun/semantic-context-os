package io.scos.collection.internal;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.text.EventLog;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.RunErrorKind;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 런타임(TS CollectionRuntime, F6·ADR-0018 §1): 설정 검증 → coverage → 점유(실패 = {@link
 * AlreadyRunningException}) → run 시작 → heartbeat → 페이지마다 assertHealthy + 페이지 커밋 → 완료. 중단: 취소면
 * cancelled(오류 0), 페이지 ≥1이면 partially_succeeded, 아니면 failed. 오류 종류는
 * CallFailureException.failure.kind / claim_lost / checkpoint_conflict / 예외 클래스명. 항상 heartbeat
 * 정지·점유 해제. 완료 기록 실패는 원인 체인과 함께 던진다. 트랜잭션은 모른다(PageCommit·RunCompletion이 연다).
 */
public final class CollectionRuntime {
  public record Report(
      RunId runId,
      SourceInstanceId source,
      RunStatus status,
      int pagesApplied,
      int upserted,
      int deleted,
      int unchanged,
      int rejected,
      int errorCount,
      Optional<String> errorKind,
      Optional<String> errorSummary) {
    static Report of(CollectionRun run) {
      return new Report(
          run.id(),
          run.source(),
          run.status(),
          run.pagesApplied(),
          run.upserted(),
          run.deleted(),
          run.unchanged(),
          run.rejected(),
          run.errorCount(),
          run.errorKind(),
          run.errorSummary());
    }
  }

  private static final Logger log = LoggerFactory.getLogger(CollectionRuntime.class);

  /** 거부 항목의 공급자 위치(JSON 텍스트)를 로그 detail의 객체로 읽는다. */
  private static final ObjectMapper LOCATOR_JSON = new ObjectMapper();

  private final SourceInstanceStore sources;
  private final SourceAdapters adapters;
  private final RawContentCapture content;
  private final ProviderCallExecutor calls;
  private final WorkClaimPort claims;
  private final CheckpointPort checkpoints;
  private final RunStore runs;
  private final PageCommit pageCommit;
  private final RunCompletion completion;
  private final OwnerId owner;
  private final LeaseHeartbeats heartbeats;
  private final Clock clock;
  private final Supplier<RunId> runIds;
  private final CollectionMetrics metrics;

  public CollectionRuntime(
      SourceInstanceStore sources,
      SourceAdapters adapters,
      RawContentCapture content,
      ProviderCallExecutor calls,
      WorkClaimPort claims,
      CheckpointPort checkpoints,
      RunStore runs,
      PageCommit pageCommit,
      RunCompletion completion,
      OwnerId owner,
      LeaseHeartbeats heartbeats,
      Clock clock,
      Supplier<RunId> runIds,
      CollectionMetrics metrics) {
    this.metrics = metrics;
    this.sources = sources;
    this.adapters = adapters;
    this.content = content;
    this.calls = calls;
    this.claims = claims;
    this.checkpoints = checkpoints;
    this.runs = runs;
    this.pageCommit = pageCommit;
    this.completion = completion;
    this.owner = owner;
    this.clock = clock;
    this.runIds = runIds;
    this.heartbeats = heartbeats;
  }

  public Report collect(SourceInstanceId id, CollectionMode mode, CancellationToken cancel) {
    SourceInstance source =
        sources
            .get(id)
            .orElseThrow(
                () -> new IllegalArgumentException("Source instance not found: " + id.value()));
    if (!source.enabled())
      throw new IllegalStateException("Source instance is disabled: " + id.value());
    SourceAdapter adapter =
        adapters
            .forKind(source.kind())
            .orElseThrow(
                () -> new IllegalStateException("Source adapter not found: " + source.kind()));
    adapter.validateConfig(source.config());
    WorkClaim claim =
        claims
            .tryClaim(id, owner, heartbeats.ttl(), clock.instant())
            .orElseThrow(
                () -> {
                  metrics.claimContention();
                  return new AlreadyRunningException(id);
                });
    // 점유 뒤 run 시작까지(체크포인트 읽기·coverage 판정·run id·heartbeat 준비·run 시작) 어디서 던지든 점유를 바로 돌려준다.
    // 다음 worker가 TTL을 기다리지 않는다. TS는 체크포인트·coverage를 점유 전에 읽어 같은 실패가 점유도 run도 남기지 않는다(B-M-1).
    // runs.start 뒤 heartbeat.start가 던지면(종료 중 스케줄러의 거부) 그 run을 그 자리에서 failed로 마감한다 — 다음 수집의 stale
    // 정리까지 running으로 남지 않는다(F6 리뷰 B n4).
    StoredCheckpoint stored;
    CollectionCoverage coverage;
    RunId runId;
    LeaseHeartbeat heartbeat;
    RunId startedRun = null;
    CollectionCoverage startedCoverage = null;
    try {
      // 체크포인트는 점유 **뒤에** 읽는다(B-M-2): 다른 인스턴스가 방금 전진시킨 위치에서 시작해 헛 HTTP·checkpoint_conflict를 피한다
      stored = checkpoints.read(id);
      coverage = adapter.coverage(source.config(), mode, stored.checkpoint());
      runId = runIds.get();
      heartbeat = heartbeats.begin(claim);
      runs.start(runId, id, clock.instant());
      startedRun = runId;
      if (coverage == CollectionCoverage.COMPLETE_SNAPSHOT)
        completion.recordSnapshotBaseline(id, runId);
      startedCoverage = coverage;
      heartbeat.start();
    } catch (RuntimeException e) {
      try {
        if (startedRun != null) failStartedRun(id, startedRun, startedCoverage, e);
      } catch (RuntimeException finishing) {
        e.addSuppressed(finishing);
      } finally {
        claims.release(claim, clock.instant());
      }
      throw e;
    }
    long[] revision = {stored.revision()};
    int[] pagesApplied = {0};
    int[] rejected = {0};
    try {
      try {
        adapter.collect(
            new CollectionInput(
                source.descriptor(), stored.checkpoint(), mode, calls, content, cancel),
            page -> {
              heartbeat.assertHealthy();
              PageCommitResult committed = pageCommit.commit(claim, runId, revision[0], page);
              revision[0] = committed.revision();
              pagesApplied[0]++;
              rejected[0] += committed.rejected();
              for (RejectedItem rejection : page.rejections()) logRejection(id, runId, rejection);
            });
        heartbeat.assertHealthy();
        RunStatus status =
            rejected[0] > 0 ? RunStatus.SUCCEEDED_WITH_REJECTIONS : RunStatus.SUCCEEDED;
        CollectionRun done =
            completion.complete(
                new RunCompletion.Input(
                    claim,
                    runId,
                    status,
                    coverage,
                    clock.instant(),
                    0,
                    Optional.empty(),
                    Optional.empty()));
        // 성공한 run의 errorKind는 스냅샷 축소 보호(ADR-0028)만 붙인다
        metrics.runFinished(status, done.errorKind());
        return Report.of(done);
      } catch (RuntimeException error) {
        RunStatus status =
            cancel.isCancelled()
                ? RunStatus.CANCELLED
                : pagesApplied[0] > 0 ? RunStatus.PARTIALLY_SUCCEEDED : RunStatus.FAILED;
        int errorCount = status == RunStatus.CANCELLED ? 0 : 1;
        String kind = classify(error);
        String summary = summary(error);
        (status == RunStatus.CANCELLED
                ? EventLog.info(log, "collection.run.cancelled")
                : EventLog.error(log, "collection.run.failed"))
            .source(id)
            .run(runId.value())
            .detail(
                EventLog.detail(
                    "status",
                    status.wire(),
                    "errorKind",
                    kind,
                    "errorSummary",
                    summary.substring(0, Math.min(summary.length(), LOGGED_SUMMARY_CHARS))))
            .log();
        CollectionRun run;
        try {
          run =
              completion.complete(
                  new RunCompletion.Input(
                      claim,
                      runId,
                      status,
                      coverage,
                      clock.instant(),
                      errorCount,
                      errorCount > 0 ? Optional.of(kind) : Optional.empty(),
                      errorCount > 0 ? Optional.of(summary) : Optional.empty()));
        } catch (RuntimeException finalization) {
          throw new IllegalStateException(
              "Run finalization failed after collection error: " + finalization.getMessage(),
              error);
        }
        metrics.runFinished(status, run.errorKind());
        return Report.of(run);
      }
    } finally {
      heartbeat.stop();
      claims.release(claim, clock.instant());
    }
  }

  /** 페이지 없이 끝난 run(heartbeat 등록 실패)을 failed로 마감한다. 관측·투영 요청이 없으므로 RunCompletion을 거치지 않는다. */
  private void failStartedRun(
      SourceInstanceId id, RunId runId, CollectionCoverage coverage, RuntimeException error) {
    String kind = classify(error);
    String summary = summary(error);
    String logged = summary.substring(0, Math.min(summary.length(), LOGGED_SUMMARY_CHARS));
    EventLog.error(log, "collection.run.failed")
        .source(id)
        .run(runId.value())
        .detail(
            EventLog.detail(
                "status", RunStatus.FAILED.wire(), "errorKind", kind, "errorSummary", logged))
        .log();
    runs.finish(
        runId,
        RunStatus.FAILED,
        coverage,
        clock.instant(),
        1,
        Optional.of(kind),
        Optional.of(logged),
        0);
    metrics.runFinished(RunStatus.FAILED, Optional.of(kind));
  }

  /** TS errorSummary.slice(0, 500) — 로그의 실패 요약 상한(UTF-16 단위). */
  static final int LOGGED_SUMMARY_CHARS = 500;

  /** TS `collection.item.rejected`: 거부 종류·사유·공급자 위치(있으면, JSON 그대로). */
  private void logRejection(SourceInstanceId id, RunId runId, RejectedItem rejection) {
    Map<String, Object> detail =
        EventLog.detail(
            "failureKind", rejection.failureKind().wire(), "message", rejection.message());
    rejection
        .providerLocatorJson()
        .ifPresent(l -> detail.put("providerLocator", LOCATOR_JSON.readTree(l)));
    EventLog.warn(log, "collection.item.rejected")
        .source(id)
        .run(runId.value())
        .detail(detail)
        .log();
  }

  static String classify(RuntimeException error) {
    if (error instanceof CallFailureException p) return providerFailureKind(p);
    if (error instanceof RunErrorKind k) return k.runErrorKind();
    if (error instanceof ClaimLostException) return "claim_lost";
    if (error instanceof CheckpointConflictException) return "checkpoint_conflict";
    if (error instanceof RunStateException) return "run_state";
    if (isStorageFailure(error)) return "storage_failed";
    return error.getClass().getSimpleName();
  }

  /**
   * 저장·로그 요약(TS classifyRunError): 공급자 호출 실패는 실패 메시지 그대로(operation·종류 접두 없이), 그 밖은 예외 메시지. 없으면 빈
   * 문자열 (TS error.message — 종류는 errorKind가 싣는다). 500자 자르기는 RunCompletion.
   */
  static String summary(RuntimeException error) {
    String message =
        error instanceof CallFailureException p ? p.failure().message() : error.getMessage();
    return message == null ? "" : message;
  }

  /**
   * 저장값은 TS와 같게, 내부 분류는 Java 타입대로(ADR-0022 §2): 자격 증명 설정 오류는 TS 해석기가 일반 Error를 던져 "Error"로 남는다.
   * 재시도하지 않는 판단은 그대로 invalid_configuration이 한다. 중단(cancelled)은 TS 해석기에 없는 경우라 그대로 둔다.
   */
  static String providerFailureKind(CallFailureException failure) {
    CallFailure.Kind kind = failure.failure().kind();
    if (failure.operation().equals(CredentialResolver.OPERATION)
        && kind == CallFailure.Kind.INVALID_CONFIGURATION) return "Error";
    return kind.wire();
  }

  /**
   * 잠금 대기 초과 55P03·교착 40P01·직렬화 실패 40001. FK 위반 23503은 넣지 않는다 — blob GC 경합(blob FK 제약)만 어댑터가 일시 장애
   * 예외로 번역하고(`collection.postgres.BlobReferences`), 그 밖의 FK 위반은 결함으로 드러난다.
   */
  private static final Set<String> TRANSIENT_SQL_STATES = Set.of("55P03", "40P01", "40001");

  /**
   * 저장소의 일시 장애(ADR-0020 §4): {@link #TRANSIENT_SQL_STATES}, Spring의 일시 DataAccess 예외, 연결 풀
   * 고갈(CannotGetJdbcConnection). 재시도 가능 — 감독 backoff가 다시 시도한다. 원인 사슬을 따라가며 SQLState로 본다(메시지 정규식
   * 금지).
   */
  public static boolean isStorageFailure(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof org.springframework.dao.TransientDataAccessException
          || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) return true;
      if (t instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if (TRANSIENT_SQL_STATES.contains(state)) return true;
      }
    }
    return false;
  }
}
