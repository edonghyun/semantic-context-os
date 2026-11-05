package io.scos.collection.internal;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** run·관측·rejection·스냅샷 완료 기록 포트. 페이지 커밋·완료 유스케이스의 트랜잭션에 참여한다. */
public interface RunStore {
  /** 같은 소스의 running run은 stale_run으로 마감하고 그 run의 본문·접근 관측만 지운다 — ADR-0008 E-5. */
  CollectionRun start(RunId id, SourceInstanceId source, Instant startedAt);

  Optional<CollectionRun> get(RunId id);

  /** 호출자 트랜잭션 안에서 행을 잠그고 running·소스 일치를 확인한다. 아니면 {@link RunStateException}. */
  CollectionRun requireRunning(RunId id, SourceInstanceId source);

  void addPage(RunId id, int upserted, int deleted, int unchanged, int rejected);

  void observe(RunId id, Collection<String> externalIds);

  Set<String> observations(RunId id);

  /** 확정 ACL 읽기 거부 파일의 run별 합집합. 빈 입력은 무동작이며 마감 뒤에도 보존한다. */
  void observeUnreadableAcls(RunId id, Set<String> externalIds);

  /** 본문·접근 결과를 독립 저장하고 본문 관측을 지운다. 접근 관측의 폐기는 병합 수명 소유자의 책임이다. */
  CollectionRun finish(
      RunId id,
      RunOutcome outcome,
      CollectionCoverage coverage,
      Instant finishedAt,
      int reconciled,
      AccessResult accessResult,
      Optional<Instant> accessFinishedAt);

  void addRejections(
      RunId id, SourceInstanceId source, Instant capturedAt, List<RejectedItem> rejections);

  List<RejectedItem> rejections(RunId id);

  /** 가장 최근에 시작한 run. */
  Optional<CollectionRun> latest(SourceInstanceId source);

  /** 요청한 인스턴스 중 RunStatus.isSuccess()인 run이 있는 것의 마지막 완료 시각. 빈 입력은 빈 맵이다. */
  Map<SourceInstanceId, Instant> lastSucceededAt(Set<SourceInstanceId> instances);

  /**
   * 최근 실패 수(F11 backoff 지수): 가장 최근 run부터 거슬러 올라가 성공(succeeded· succeeded_with_rejections)을 만나기 전까지의
   * failed·partially_succeeded 수. running·cancelled는 세지 않는다 — 종료(SIGTERM)가 backoff를 키우지 않는다.
   */
  int recentFailureCount(SourceInstanceId source);

  /** 이 run의 전체 정리는 지정 범위 세대의 부재 검증을 통과해야 한다. */
  void requireAbsenceVerification(RunId run, String fingerprint);

  Optional<String> absenceRequirement(RunId run);

  record AbsenceValidation(String fingerprint, String candidateToken, Set<String> exclusions) {
    public AbsenceValidation {
      exclusions = Set.copyOf(exclusions);
    }
  }

  /** 같은 범위 세대로 검증을 요구한 running run에만 저장한다. */
  void recordAbsenceValidation(RunId run, AbsenceValidation validation);

  Optional<AbsenceValidation> absenceValidation(RunId run);

  record SnapshotCompletion(Instant completedAt, int deletionCount) {}

  default void recordSnapshotCompletion(
      SourceInstanceId source, Instant completedAt, int deletionCount) {
    recordSnapshotCompletion(source, completedAt, deletionCount, Optional.empty());
  }

  void recordSnapshotCompletion(
      SourceInstanceId source,
      Instant completedAt,
      int deletionCount,
      Optional<String> fingerprint);

  boolean hasReconciliationBaseline(SourceInstanceId source, String fingerprint);

  Optional<SnapshotCompletion> lastSnapshotCompletion(SourceInstanceId source);
}
