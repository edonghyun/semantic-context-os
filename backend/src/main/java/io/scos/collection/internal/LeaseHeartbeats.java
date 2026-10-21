package io.scos.collection.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;

/**
 * 점유 갱신 한 벌(F3·ADR-0008): TTL·스케줄러·지표를 한 곳에 묶는다. 수집 런타임과 투영이 같은 것을 받아, 어느 쪽 heartbeat든 같은 TTL로 점유하고
 * 같은 지표({@code scos.collection.lease_heartbeat})에 남는다.
 */
public final class LeaseHeartbeats {
  private final WorkClaimPort claims;
  private final Duration ttl;
  private final Clock clock;
  private final TaskScheduler scheduler;
  private final CollectionMetrics metrics;

  public LeaseHeartbeats(
      WorkClaimPort claims,
      Duration ttl,
      Clock clock,
      TaskScheduler scheduler,
      CollectionMetrics metrics) {
    this.claims = claims;
    this.ttl = ttl;
    this.clock = clock;
    this.scheduler = scheduler;
    this.metrics = metrics;
  }

  /** 점유 TTL — 점유를 잡을 때와 갱신할 때 같은 값. */
  public Duration ttl() {
    return ttl;
  }

  /** claim의 heartbeat(아직 시작 전). */
  public LeaseHeartbeat begin(WorkClaim claim) {
    return new LeaseHeartbeat(claims, claim, ttl, clock, scheduler, metrics);
  }
}
