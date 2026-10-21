package io.scos.collection.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.BackgroundThread;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LeaseHeartbeat 테스트의 연결을 소유한다: renew 호출을 세는 메모리 점유 포트, 수동 스케줄러, 가짜 시계, 진행 중 renew를 붙잡는 gate, 다른
 * 스레드의 stop·tick·renew, 다음 renew 한 번을 실패시키는 예외. 본문은 "언제 tick·stop하면 점유와 타이머가 어떻게 되는가"만 쓴다.
 */
final class HeartbeatScenario implements AutoCloseable {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final Duration TTL = Duration.ofSeconds(30);
  static final Duration WITHIN_TTL = Duration.ofSeconds(10);
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final AtomicInteger renewCalls = new AtomicInteger();
  private final HoldGate renewGate = new HoldGate(WAIT_LIMIT);
  private final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  private final AtomicReference<RuntimeException> nextRenewFailure = new AtomicReference<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private volatile boolean renewsHeld;
  private final WorkClaim claim;
  private final LeaseHeartbeat heartbeat;

  HeartbeatScenario() {
    claim =
        claims
            .tryClaim(SOURCE, new OwnerId("w1"), TTL, clock.instant())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 빈 소스를 점유하지 못했다"));
    heartbeat =
        new LeaseHeartbeat(
            countingPort(), claim, TTL, clock, scheduler, CollectionMetrics.of(registry));
  }

  /** 다음 renew가 releaseRenew()까지 진행 중으로 멈춘다. */
  HeartbeatScenario renewsHeld() {
    renewsHeld = true;
    return this;
  }

  /** 다음 renew 한 번이 점유 저장소에 닿지 못하고 failure를 던진다. 그 뒤 renew는 다시 저장소에 위임된다. */
  HeartbeatScenario nextRenewFails(RuntimeException failure) {
    nextRenewFailure.set(failure);
    return this;
  }

  LeaseHeartbeat start() {
    heartbeat.start();
    return heartbeat;
  }

  void tick() {
    scheduler.tick();
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  void stop() {
    heartbeat.stop();
  }

  void releaseClaim() {
    claims.release(claim, clock.instant());
  }

  /** 다른 스레드에서 tick을 돌리고 renew가 진행 중이 될 때까지 기다린다. */
  CompletableFuture<Void> tickWithRenewInProgress() {
    CompletableFuture<Void> tick = CompletableFuture.runAsync(scheduler::tick);
    renewGate.awaitHeld("renew 진행 중");
    return tick;
  }

  /** 다른 스레드에서 stop을 부르고 그 스레드가 대기에 들어가거나 끝날 때까지 기다린다. */
  BackgroundThread stopInBackground() {
    BackgroundThread stopping = BackgroundThread.start("stop", heartbeat::stop);
    Eventually.await("stop 스레드가 대기하거나 끝남", stopping::isBlockedOrDone, WAIT_LIMIT);
    return stopping;
  }

  void releaseRenew() {
    renewGate.release();
  }

  /** 다른 스레드에서 renew를 직접 부르고 끝나기를 기다린다(정지 뒤 뒤늦은 renew). */
  void renewFromOtherThread() throws Exception {
    CompletableFuture.runAsync(heartbeat::renew).get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  static void await(CompletableFuture<Void> f) throws Exception {
    f.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  // ---- 관찰 ----

  Instant now() {
    return clock.instant();
  }

  WorkClaim originalClaim() {
    return claim;
  }

  Instant storedExpiry() {
    return claims.current(SOURCE).orElseThrow().expiresAt();
  }

  boolean storedClaimActive() {
    return claims.current(SOURCE).orElseThrow().isActiveAt(clock.instant());
  }

  int renewCalls() {
    return renewCalls.get();
  }

  int registeredTasks() {
    return scheduler.tasks().size();
  }

  Duration registeredPeriod() {
    return scheduler.tasks().get(0).period();
  }

  long liveTasks() {
    return scheduler.liveTasks();
  }

  SimpleMeterRegistry registry() {
    return registry;
  }

  @Override
  public void close() {
    renewGate.release();
  }

  /** renew 호출을 세고, renewsHeld면 진행 중으로 멈추고, 실패가 걸려 있으면 한 번 던지는 점유 포트(나머지는 메모리 구현에 위임). */
  private WorkClaimPort countingPort() {
    return new WorkClaimPort() {
      @Override
      public Optional<WorkClaim> tryClaim(
          SourceInstanceId source, OwnerId owner, Duration ttl, Instant now) {
        return claims.tryClaim(source, owner, ttl, now);
      }

      @Override
      public WorkClaim renew(WorkClaim c, Duration ttl, Instant now) {
        renewCalls.incrementAndGet();
        if (renewsHeld) renewGate.hold();
        RuntimeException failure = nextRenewFailure.getAndSet(null);
        if (failure != null) throw failure;
        return claims.renew(c, ttl, now);
      }

      @Override
      public void release(WorkClaim c, Instant now) {
        claims.release(c, now);
      }

      @Override
      public void requireActive(WorkClaim c, Instant now) {
        claims.requireActive(c, now);
      }

      @Override
      public Optional<WorkClaim> current(SourceInstanceId source) {
        return claims.current(source);
      }
    };
  }
}
