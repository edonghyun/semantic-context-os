package io.scos.collection.internal;

import io.scos.engine.support.text.EventLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.TaskScheduler;

/**
 * 점유 갱신(TS LeaseHeartbeat, F3·ADR-0008): TTL/2마다 renew. 실패는 플래그에 남고 {@link #assertHealthy()}가 다음
 * 페이지 전에 드러낸다 — heartbeat 성공은 안전성의 근거가 아니다(저장 경계가 검사한다, 불변식 5). {@link #stop()}은 진행 중인 renew가 끝날
 * 때까지 기다린다 — 해제 뒤에 늦은 renew가 점유를 되살리지 않는다(TS `await pendingRenewal`, 리뷰 B-I-3). 점유 행의 잠금을 얻지 못한
 * renew({@link CannotAcquireLockException} — 쓰기 트랜잭션의 requireActive가 행을 쥔 동안 lock_timeout)는 실패로 남기지
 * 않고 다음 tick이 다시 시도한다: TTL 안의 한 번 놓친 갱신은 무해하고, 정말 만료되면 저장 경계가 거부한다. TS SQLite에는 없는 경우다.
 */
public final class LeaseHeartbeat {
  private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);
  private final WorkClaimPort claims;
  private final Duration ttl;
  private final Clock clock;
  private final TaskScheduler scheduler;
  private final AtomicReference<WorkClaim> claim;
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
  private final ReentrantLock renewing = new ReentrantLock();
  private volatile boolean stopped;
  private volatile ScheduledFuture<?> timer;
  private final CollectionMetrics metrics;

  LeaseHeartbeat(
      WorkClaimPort claims,
      WorkClaim initial,
      Duration ttl,
      Clock clock,
      TaskScheduler scheduler,
      CollectionMetrics metrics) {
    this.metrics = metrics;
    this.claims = claims;
    this.claim = new AtomicReference<>(initial);
    this.ttl = ttl;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  public Duration interval() {
    return Duration.ofMillis(Math.max(1, ttl.toMillis() / 2));
  }

  public void start() {
    timer = scheduler.scheduleAtFixedRate(this::renew, interval());
  }

  /** 한 번 갱신(스케줄러가 부른다). 정지 뒤에는 아무것도 하지 않는다. */
  void renew() {
    renewing.lock();
    try {
      if (stopped || failure.get() != null) return;
      Instant startedAt = clock.instant();
      claim.set(claims.renew(claim.get(), ttl, startedAt));
      metrics.heartbeat(CollectionMetrics.HeartbeatOutcome.OK);
      Duration elapsed = Duration.between(startedAt, clock.instant());
      if (elapsed.compareTo(interval().dividedBy(2)) > 0)
        EventLog.warn(log, "lease.heartbeat.slow")
            .source(claim.get().source())
            .detail(EventLog.detail("elapsedMs", elapsed.toMillis()))
            .log();
    } catch (CannotAcquireLockException e) {
      metrics.heartbeat(CollectionMetrics.HeartbeatOutcome.FAILED);
      EventLog.warn(log, "lease.heartbeat.contended")
          .source(claim.get().source())
          .detail(EventLog.detail("message", failureMessage(e)))
          .log();
    } catch (RuntimeException e) {
      metrics.heartbeat(CollectionMetrics.HeartbeatOutcome.FAILED);
      EventLog.warn(log, "lease.heartbeat.failed")
          .source(claim.get().source())
          .detail(EventLog.detail("message", failureMessage(e)))
          .log();
      failure.set(e);
      cancelTimer();
    } finally {
      renewing.unlock();
    }
  }

  public void assertHealthy() {
    RuntimeException f = failure.get();
    if (f != null) throw f;
  }

  public WorkClaim claim() {
    return claim.get();
  }

  /**
   * 타이머를 끄고 진행 중인 renew를 기다린다. 돌아온 뒤에는 어떤 renew도 점유를 바꾸지 않는다. renew는 점유 행 잠금을 최대 TTL/4
   * 기다리지만(PostgreSQL 어댑터), stop은 run이 끝난 뒤 — 점유 행을 쥔 쓰기 트랜잭션이 없을 때 — 불리므로 곧 돌아온다.
   */
  public void stop() {
    renewing.lock();
    try {
      stopped = true;
      cancelTimer();
    } finally {
      renewing.unlock();
    }
  }

  private void cancelTimer() {
    ScheduledFuture<?> t = timer;
    if (t != null) t.cancel(false);
  }

  /** TS `error instanceof Error ? error.message : String(error)`: 메시지가 없으면 예외 문자열("null" 대신). */
  static String failureMessage(RuntimeException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
