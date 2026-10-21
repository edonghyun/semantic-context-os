package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;

/**
 * 스냅샷 축소 보호 상태 포트(ADR-0028): 소스별 마지막 보호 판정과 운영자의 축소 허용 표시. 완료 유스케이스({@link RunCompletion})의 트랜잭션에
 * 참여한다 — 보호 기록·허용 소모가 정리·run 마감과 함께 커밋되거나 함께 사라진다.
 */
public interface SnapshotGuardStore {
  /**
   * 정리를 건너뛴 판정 하나: 어느 run이 언제, 정리 직전 active 몇 건 중 몇 건을 관측하지 못했는지, 그리고 정리 없이 이어진 보호가 이것까지 몇 번째인지(정리가
   * 일어나면 기록이 지워져 1부터 다시 센다).
   */
  record Guarded(
      RunId run, Instant guardedAt, int unobservedCount, int activeCount, int consecutiveCount) {}

  /**
   * 운영자의 축소 허용(감사 기록). 소모되거나 만료되기 전에는 다음 succeeded + complete_snapshot 완료 한 번이 보호 없이 정리한다.
   *
   * @param consumedBy 허용을 쓴 run(소모 전에는 empty)
   * @param expiredAt 쓰이지 않은 채 만료로 무시된 시각(ADR-0028 §4)
   */
  record Acceptance(
      Instant acceptedAt,
      Optional<Instant> consumedAt,
      Optional<RunId> consumedBy,
      Optional<Instant> expiredAt) {
    public boolean pending() {
      return consumedAt.isEmpty() && expiredAt.isEmpty();
    }
  }

  /** 마지막 보호 판정을 남긴다(이전 것을 덮는다). */
  void recordGuarded(SourceInstanceId source, Guarded guarded);

  /** 정리가 일어나면 보호 상태가 풀린다. */
  void clearGuarded(SourceInstanceId source);

  Optional<Guarded> lastGuarded(SourceInstanceId source);

  /** 허용 표시를 남긴다. 아직 소모되지 않은 표시가 있으면 시각만 새로 바뀐다. */
  void acceptShrink(SourceInstanceId source, Instant at);

  /** 마지막 허용 표시(소모된 것 포함). */
  Optional<Acceptance> lastAcceptance(SourceInstanceId source);

  /**
   * acceptedAt에 남긴 허용 표시가 아직 대기 중이면 만료로 표시하고 true. 그 사이 다시 허용해 시각이 바뀌었거나 대기 중이 아니면 아무것도 바꾸지 않고
   * false — 판정이 읽은 옛 허용 대신 막 남긴 새 허용을 만료시키지 않는다.
   */
  boolean expireAcceptance(SourceInstanceId source, Instant acceptedAt, Instant at);

  /**
   * complete_snapshot run이 시작할 때의 active 수(정리 판정의 분모 M — 이번 run이 새로 넣은 항목은 들어가지 않는다, ADR-0028 §1).
   * 소스마다 마지막 하나만 둔다.
   */
  void recordBaseline(SourceInstanceId source, RunId run, int activeCount);

  /** 이 run이 시작할 때 남긴 active 수. 다른 run의 것이거나 없으면 empty. */
  Optional<Integer> baseline(SourceInstanceId source, RunId run);

  /** 대기 중인 허용 표시가 있으면 이 run이 쓴 것으로 소모하고 true. 없으면 아무것도 바꾸지 않고 false. */
  boolean consumeAcceptance(SourceInstanceId source, RunId run, Instant at);
}
