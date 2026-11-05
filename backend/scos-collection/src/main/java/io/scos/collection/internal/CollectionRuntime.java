package io.scos.collection.internal;

import io.scos.engine.EventLog;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.logging.EventValues;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 런타임(F6·ADR-0018 §1): 설정 검증 → 점유(실패 = {@link AlreadyRunningException}) → run 시작(체크포인트·coverage·
 * heartbeat) → 페이지마다 assertHealthy + 페이지 커밋 → 완료. 중단 결과는 {@link RunOutcome#interrupted}, 오류 종류·요약은
 * {@link RunFailure}. 항상 heartbeat 정지·점유 해제. 완료 기록 실패는 원인 체인과 함께 던진다. 트랜잭션은 모른다(PageCommit·
 * RunCompletion이 연다).
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
      Optional<String> errorSummary,
      Set<String> aclUnreadable) {
    public Report {
      aclUnreadable = Set.copyOf(aclUnreadable);
    }

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
          run.errorSummary(),
          run.aclUnreadable());
    }
  }

  private static final Logger log = LoggerFactory.getLogger(CollectionRuntime.class);

  /** 거부 항목·실패의 공급자 위치(JSON 텍스트)를 로그 detail의 객체로 읽는다. */
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
  private final AccessCollection access;
  private final AccessCompletion accessCompletion;
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
      AccessCollection access,
      AccessCompletion accessCompletion,
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
    this.access = access;
    this.accessCompletion = accessCompletion;
    this.owner = owner;
    this.clock = clock;
    this.runIds = runIds;
    this.heartbeats = heartbeats;
  }

  public Report collect(SourceInstanceId id, CollectionMode mode, CancellationToken cancel) {
    SourceInstance source = enabledSource(id);
    SourceAdapter adapter = adapterFor(source);
    adapter.validateConfig(source.config());
    WorkClaim claim = claim(id);
    StartedRun run = start(claim, source, adapter, mode);
    try {
      return run.collectWith(adapter, source, mode, cancel);
    } finally {
      run.heartbeat().stop();
      claims.release(claim, clock.instant());
    }
  }

  private SourceInstance enabledSource(SourceInstanceId id) {
    SourceInstance source =
        sources
            .get(id)
            .orElseThrow(
                () -> new IllegalArgumentException("Source instance not found: " + id.value()));
    if (!source.enabled())
      throw new IllegalStateException("Source instance is disabled: " + id.value());
    return source;
  }

  private SourceAdapter adapterFor(SourceInstance source) {
    return adapters
        .forKind(source.kind())
        .orElseThrow(() -> new IllegalStateException("Source adapter not found: " + source.kind()));
  }

  private WorkClaim claim(SourceInstanceId id) {
    return claims
        .tryClaim(id, owner, heartbeats.ttl(), clock.instant())
        .orElseThrow(
            () -> {
              metrics.claimContention();
              return new AlreadyRunningException(id);
            });
  }

  /**
   * 점유 뒤 run 시작까지(체크포인트 읽기·coverage 판정·run id·heartbeat 준비·run 시작) 어디서 던지든 점유를 바로 돌려준다 — 다음 worker가
   * TTL을 기다리지 않는다. 체크포인트는 점유 **뒤에** 읽는다(B-M-2): 다른 인스턴스가 방금 전진시킨 위치에서 시작해 헛
   * HTTP·checkpoint_conflict를 피한다. runs.start 뒤 heartbeat.start가 던지면(종료 중 스케줄러의 거부) 그 run을 그 자리에서
   * failed로 마감한다 — 다음 수집의 stale 정리까지 running으로 남지 않는다.
   */
  private StartedRun start(
      WorkClaim claim, SourceInstance source, SourceAdapter adapter, CollectionMode mode) {
    SourceInstanceId id = source.id();
    RunId startedRun = null;
    CollectionCoverage startedCoverage = null;
    try {
      StoredCheckpoint stored = checkpoints.read(id);
      CollectionCoverage coverage = adapter.coverage(source.config(), mode, stored.checkpoint());
      Optional<SnapshotScope> verification =
          coverage == CollectionCoverage.COMPLETE_SNAPSHOT
              ? adapter.snapshotVerification(source.config())
              : Optional.empty();
      RunId runId = runIds.get();
      LeaseHeartbeat heartbeat = heartbeats.begin(claim);
      runs.start(runId, id, clock.instant());
      startedRun = runId;
      // 시작한 run을 마감할 coverage — 정리 기준 기록이 실패해도 마감에 넘긴다
      startedCoverage = coverage;
      if (coverage == CollectionCoverage.COMPLETE_SNAPSHOT)
        completion.recordSnapshotBaseline(id, runId);
      verification.ifPresent(scope -> runs.requireAbsenceVerification(runId, scope.fingerprint()));
      heartbeat.start();
      return new StartedRun(
          claim, runId, stored, coverage, heartbeat, source.descriptor(), verification);
    } catch (RuntimeException e) {
      try {
        // 둘은 함께 정해진다(사이에 던질 수 있는 호출이 없다)
        if (startedRun != null && startedCoverage != null)
          failStartedRun(id, startedRun, startedCoverage, e);
      } catch (RuntimeException finishing) {
        e.addSuppressed(finishing);
      } finally {
        claims.release(claim, clock.instant());
      }
      throw e;
    }
  }

  /** 페이지 없이 끝난 run(heartbeat 등록 실패)을 failed로 마감한다. 관측·투영 요청이 없으므로 RunCompletion을 거치지 않는다. */
  private void failStartedRun(
      SourceInstanceId id, RunId runId, CollectionCoverage coverage, RuntimeException error) {
    RunFailure failure = RunFailure.of(error);
    logRunEnded(id, runId, RunStatus.FAILED, failure);
    runs.finish(
        runId,
        RunOutcome.failed(RunStatus.FAILED, failure.kind(), failure.summary()),
        coverage,
        clock.instant(),
        0,
        AccessResult.SKIPPED,
        Optional.empty());
    metrics.runFinished(RunStatus.FAILED, Optional.of(failure.kind()));
  }

  /** 시작한 run 하나: 어댑터가 낸 페이지를 커밋하고, 끝나거나 멈추면 결과로 마감한다. */
  private final class StartedRun {
    private final WorkClaim claim;
    private final RunId runId;
    private final StoredCheckpoint stored;
    private final CollectionCoverage coverage;
    private final LeaseHeartbeat heartbeat;
    private final SourceDescriptor expectedSource;
    private final Optional<SnapshotScope> verification;

    StartedRun(
        WorkClaim claim,
        RunId runId,
        StoredCheckpoint stored,
        CollectionCoverage coverage,
        LeaseHeartbeat heartbeat,
        SourceDescriptor expectedSource,
        Optional<SnapshotScope> verification) {
      this.claim = claim;
      this.runId = runId;
      this.stored = stored;
      this.coverage = coverage;
      this.heartbeat = heartbeat;
      this.expectedSource = expectedSource;
      this.verification = verification;
    }

    LeaseHeartbeat heartbeat() {
      return heartbeat;
    }

    Report collectWith(
        SourceAdapter adapter,
        SourceInstance source,
        CollectionMode mode,
        CancellationToken cancel) {
      PageProgress progress = new PageProgress(stored.revision());
      RunOutcome body = collectBody(adapter, source, mode, cancel, progress);
      AccessCollection.Result collected =
          access.collect(expectedSource, adapter, claim, runId, heartbeat, cancel);
      AccessResult applied = finishAccess(collected);
      Instant accessEnded = clock.instant();
      CollectionRun done = completion.complete(input(body, applied, accessEnded));
      if (body.status().isSuccess() && !done.status().isSuccess())
        logRunEnded(
            claim.source(),
            runId,
            done.status(),
            new RunFailure(
                done.errorKind().orElseThrow(),
                done.errorSummary().orElse(""),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
      metrics.runFinished(done.status(), done.errorKind());
      reportReconciliation(done);
      return Report.of(done);
    }

    private AccessResult finishAccess(AccessCollection.Result collected) {
      if (!heartbeat.healthy()) {
        if (collected.result() == AccessResult.SKIPPED) return collected.result();
        if (heartbeat.claimLost()) return AccessResult.CLAIM_LOST;
        if (collected.directory() == AccessMerge.COMPLETE)
          collected = new AccessCollection.Result(AccessResult.INCOMPLETE, AccessMerge.INCOMPLETE);
      }
      return accessCompletion.finish(claim, runId, expectedSource, collected, clock.instant());
    }

    private RunOutcome collectBody(
        SourceAdapter adapter,
        SourceInstance source,
        CollectionMode mode,
        CancellationToken cancel,
        PageProgress progress) {
      try {
        adapter.collect(
            new CollectionInput(
                source.descriptor(),
                stored.checkpoint(),
                mode,
                calls,
                content,
                cancel,
                completion.state(claim.source(), runId, verification)),
            progress);
        heartbeat.assertHealthy();
        return RunOutcome.succeeded(progress.rejected);
      } catch (RuntimeException error) {
        RunFailure failure = RunFailure.of(error);
        RunOutcome outcome =
            RunOutcome.interrupted(
                cancel.isCancelled(), progress.pagesApplied, failure.kind(), failure.summary());
        logRunEnded(claim.source(), runId, outcome.status(), failure);
        return outcome;
      }
    }

    private void reportReconciliation(CollectionRun run) {
      try {
        completion.reconciliation(run).ifPresent(summary -> logReconciliation(run, summary));
      } catch (RuntimeException error) {
        EventLog.warn(log, "collection.reconciliation.report_failed")
            .source(run.source())
            .run(run.id().value())
            .cause(error)
            .log();
      }
    }

    private void logReconciliation(CollectionRun run, ReconciliationDiscovery.Summary summary) {
      metrics.reconciliation(summary);
      Object ratio = summary.missRatio().isPresent() ? summary.missRatio().getAsDouble() : "N/A";
      Object successAge =
          runs.lastSnapshotCompletion(run.source())
              .<Object>map(
                  cleanup -> Duration.between(cleanup.completedAt(), clock.instant()).toSeconds())
              .orElse("N/A");
      EventLog.info(log, "collection.reconciliation.completed")
          .source(run.source())
          .run(run.id().value())
          .detail(
              EventValues.detail(
                  "eligiblePages",
                  summary.eligiblePages(),
                  "missedPages",
                  summary.missedPages(),
                  "missRatio",
                  ratio,
                  "partial",
                  summary.partial(),
                  "discoveries",
                  summary.discovered(),
                  "lastSuccessAgeSeconds",
                  successAge))
          .log();
    }

    private RunCompletion.Input input(
        RunOutcome outcome, AccessResult result, Instant accessEnded) {
      return new RunCompletion.Input(
          claim,
          runId,
          outcome,
          coverage,
          clock.instant(),
          result,
          result == AccessResult.SKIPPED ? Optional.empty() : Optional.of(accessEnded),
          Optional.of(expectedSource),
          verification);
    }

    /** 페이지마다 heartbeat를 확인하고 커밋한다. 다음 커밋이 기대할 revision과 반영한 페이지·거부 수를 센다. */
    private final class PageProgress implements PageSink {
      private long revision;
      private int pagesApplied;
      private int rejected;

      PageProgress(long revision) {
        this.revision = revision;
      }

      @Override
      public void accept(CollectionPage page) {
        heartbeat.assertHealthy();
        PageCommitResult committed =
            pageCommit.commit(claim, runId, revision, page, expectedSource);
        revision = committed.revision();
        pagesApplied++;
        rejected += committed.rejected();
        for (RejectedItem rejection : page.rejections())
          logRejection(claim.source(), runId, rejection);
      }
    }
  }

  /**
   * run이 오류로 끝났다(취소면 info, 아니면 error). 요약은 저장 상한까지만 싣는다. 실패면 공급자 위치(있는 것만: operation, 응답 검사 위반
   * violation·itemIndex, providerLocator)를 덧붙인다.
   */
  private static void logRunEnded(
      SourceInstanceId id, RunId runId, RunStatus status, RunFailure failure) {
    Map<String, Object> detail =
        EventValues.detail(
            "status",
            status.wire(),
            "errorKind",
            failure.kind(),
            "errorSummary",
            failure.loggedSummary());
    if (status == RunStatus.CANCELLED) {
      EventLog.info(log, "collection.run.cancelled")
          .source(id)
          .run(runId.value())
          .detail(detail)
          .log();
      return;
    }
    failure.operation().ifPresent(o -> detail.put("operation", o));
    failure
        .violation()
        .ifPresent(
            v -> {
              detail.put("violation", v.check());
              v.itemIndex().ifPresent(i -> detail.put("itemIndex", i));
            });
    failure
        .providerLocatorJson()
        .ifPresent(l -> detail.put("providerLocator", LOCATOR_JSON.readTree(l)));
    EventLog.error(log, "collection.run.failed").source(id).run(runId.value()).detail(detail).log();
  }

  /** 거부 종류·사유·공급자 위치(있으면, JSON 그대로). */
  private static void logRejection(SourceInstanceId id, RunId runId, RejectedItem rejection) {
    Map<String, Object> detail =
        EventValues.detail(
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
}
