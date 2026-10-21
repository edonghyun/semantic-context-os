package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 작업 점유 포트(CONTRACTS F1·F3, 불변식 5). 계약은 {@code WorkClaimContract}가 정의하고 모든 어댑터가 통과한다.
 *
 * <ul>
 *   <li>같은 소스를 동시에 점유하려는 둘 중 하나만 성공한다.
 *   <li>만료된 점유는 다른 owner가 인계받고 token이 증가한다.
 *   <li>{@link #requireActive}는 저장 경계에서 token·만료를 검사한다 — heartbeat 성공은 안전성의 근거가 아니다.
 *   <li>시각은 호출자가 넘긴다(시계 주입). 어댑터는 저장소 시계를 쓰지 않는다.
 * </ul>
 */
public interface WorkClaimPort {
  Optional<WorkClaim> tryClaim(SourceInstanceId source, OwnerId owner, Duration ttl, Instant now);

  /**
   * 같은 owner·token이고 아직 만료 전일 때만 연장한다. 아니면 {@link ClaimLostException}. 다른 트랜잭션이 점유 행을 쥐고 있어 잠금을 얻지
   * 못하면 {@link org.springframework.dao.CannotAcquireLockException} — 점유를 잃은 것이 아니라 일시 경합이다.
   */
  WorkClaim renew(WorkClaim claim, Duration ttl, Instant now);

  /** 보유자만 해제한다. 보유자가 아니면 아무 일도 하지 않는다. */
  void release(WorkClaim claim, Instant now);

  /** 쓰기 직전 저장 경계 검사. 호출자의 트랜잭션 안에서 점유 행을 잠그고 token·만료를 비교한다. 같은 소스의 동시 커밋은 여기서 직렬화된다. */
  void requireActive(WorkClaim claim, Instant now);

  Optional<WorkClaim> current(SourceInstanceId source);
}
