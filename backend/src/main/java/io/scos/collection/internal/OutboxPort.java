package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 커밋 후 전달 포트(ADR-0007). 요청은 호출자의 트랜잭션 안에서 등록되어 커밋과 함께만 보인다. 같은 소스의 반복 요청은 하나로 합쳐지고(큐 상한 = 소스 수)
 * 등록마다 version이 오른다. 완료·실패 기록은 **처리를 시작할 때 읽은 version일 때만** 적용된다 — 처리 중 들어온 요청은 지워지지 않는다(리뷰 B-C-1).
 * 실패는 attempts·backoff·parked로 남고(정책은 호출자 소유), 새 요청(새 데이터)은 attempts·backoff·parked를 리셋한다 — 새 데이터는
 * 새 시도 예산을 받는다. consecutiveFailures는 version과 무관하게 실패마다 오르고 요청이 완료(삭제)될 때만 0으로 돌아간다 — 활발한 소스에서
 * 결정적으로 실패하는 투영의 운영 신호(재검토2 F-3). 재기동 시 {@link #pending}이 재개 목록이다.
 */
public interface OutboxPort {
  record Request(
      SourceInstanceId source,
      long version,
      Instant requestedAt,
      int attempts,
      int consecutiveFailures,
      Optional<Instant> nextAttemptAt,
      boolean parked,
      Optional<String> lastError) {}

  /** 운영 요약(게이지): parked가 아닌 요청 수(backoff 중 포함), parked 수, consecutiveFailures 최댓값(요청이 없으면 0). */
  record Summary(int pending, int parked, int maxConsecutiveFailures) {}

  void requestProjection(SourceInstanceId source, Instant now);

  /** 모든 요청(요청 시각 순) — 재기동 재개 목록. parked 포함. */
  List<SourceInstanceId> pending();

  /** 지금 시도할 요청: parked가 아니고 backoff가 없거나 지난 것(요청 시각 순). */
  List<SourceInstanceId> due(Instant now);

  Optional<Request> request(SourceInstanceId source);

  Summary summary();

  /** version이 seenVersion과 같을 때만 지운다. 지웠으면 true. */
  boolean complete(SourceInstanceId source, long seenVersion);

  /**
   * version이 seenVersion과 같을 때만 attempts+1·consecutiveFailures+1·backoff·parked·last_error를 기록한다.
   * 기록했으면 true.
   */
  boolean recordFailure(
      SourceInstanceId source,
      long seenVersion,
      Instant now,
      String error,
      ProjectionRetryPolicy policy);
}
