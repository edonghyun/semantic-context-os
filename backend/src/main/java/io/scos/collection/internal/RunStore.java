package io.scos.collection.internal;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * run·관측·rejection·스냅샷 완료 기록 포트(TS SyncRunStore + 관측/rejection/snapshotCompletions). 페이지 커밋·완료
 * 유스케이스의 트랜잭션에 참여한다.
 */
public interface RunStore {
  /**
   * 같은 소스에 running으로 남은 run(죽은 프로세스)은 failed(errorKind=stale_run)로 마감하고 관측을 지운다 — TS
   * startRun·ADR-0008 E-5.
   */
  CollectionRun start(RunId id, SourceInstanceId source, Instant startedAt);

  Optional<CollectionRun> get(RunId id);

  /** 호출자 트랜잭션 안에서 행을 잠그고 running·소스 일치를 확인한다. 아니면 {@link RunStateException}. */
  CollectionRun requireRunning(RunId id, SourceInstanceId source);

  void addPage(RunId id, int upserted, int deleted, int unchanged, int rejected);

  void observe(RunId id, Collection<String> externalIds);

  Set<String> observations(RunId id);

  CollectionRun finish(
      RunId id,
      RunStatus status,
      CollectionCoverage coverage,
      Instant finishedAt,
      int errorCount,
      Optional<String> errorKind,
      Optional<String> errorSummary,
      int reconciled);

  void addRejections(
      RunId id, SourceInstanceId source, Instant capturedAt, List<RejectedItem> rejections);

  List<RejectedItem> rejections(RunId id);

  /** 가장 최근에 시작한 run. */
  Optional<CollectionRun> latest(SourceInstanceId source);

  /**
   * 최근 실패 수(F11 backoff 지수, TS recentFailureCount): 가장 최근 run부터 거슬러 올라가 성공(succeeded·
   * succeeded_with_rejections)을 만나기 전까지의 failed·partially_succeeded 수. running·cancelled는 세지 않는다 —
   * 종료(SIGTERM)가 backoff를 키우지 않는다.
   */
  int recentFailureCount(SourceInstanceId source);

  record SnapshotCompletion(Instant completedAt, int deletionCount) {}

  void recordSnapshotCompletion(SourceInstanceId source, Instant completedAt, int deletionCount);

  Optional<SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source);
}
