package io.scos.server;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.TokenHash;
import io.scos.store.memory.ObservedUserTokens;
import io.scos.support.Eventually;
import io.scos.support.HoldGate;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 사용 기록의 시각·수동 주기·저장 상태를 결정적으로 연결한다. */
final class TokenUsageScenario implements AutoCloseable {
  static final Instant FIRST = Instant.parse("2026-10-27T00:00:00Z");
  static final Instant LATEST = Instant.parse("2026-10-27T00:00:30Z");
  static final Instant EARLIER = Instant.parse("2026-10-27T00:00:10Z");
  static final TokenHash TOKEN = TokenHash.of("usage-token-fixture");
  static final TokenHash OTHER = TokenHash.of("other-usage-token-fixture");
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private final HoldGate writeGate = new HoldGate(WAIT_LIMIT);
  private final AtomicBoolean holdWrites = new AtomicBoolean();
  private final AtomicBoolean failWrite = new AtomicBoolean();
  private final ObservedUserTokens tokens = new ObservedUserTokens(this::beforeUsageWrite);
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final TokenUsageRecorder recorder = new TokenUsageRecorder(tokens, scheduler);
  private Optional<CompletableFuture<Void>> flushing = Optional.empty();

  TokenUsageScenario() {
    tokens.seed(AccessDirectory.empty().withUser(new User("alice", "alice@example.com", true)));
    tokens.store(TOKEN, "alice", "first", FIRST);
    tokens.store(OTHER, "alice", "other", FIRST);
  }

  void record(TokenHash hash, Instant at) {
    recorder.record(hash, at);
  }

  void tick() {
    scheduler.tick();
  }

  void holdFlush() {
    holdWrites.set(true);
    flushing = Optional.of(CompletableFuture.runAsync(this::tick));
    writeGate.awaitHeld("사용 기록 쓰기가 저장소에 도착함");
  }

  void givenWriteFailure() {
    failWrite.set(true);
  }

  void failFlush() {
    givenWriteFailure();
    try {
      tick();
    } catch (IllegalStateException failure) {
      if (failure.getMessage().equals("usage-write-fixture")) return;
      throw failure;
    }
    throw new IllegalStateException("given 준비 실패: 사용 기록 쓰기가 실패해야 한다");
  }

  boolean recordDuringFlush(TokenHash hash, Instant at) throws InterruptedException {
    Thread recording =
        Thread.ofPlatform().name("token-usage-record").unstarted(() -> record(hash, at));
    recording.start();
    boolean completed;
    try {
      Eventually.await(
          "사용 기록 완료 또는 기록기 모니터 대기",
          () -> !recording.isAlive() || recording.getState() == Thread.State.BLOCKED,
          WAIT_LIMIT);
      completed = !recording.isAlive();
    } finally {
      // 실패하는 명세에서도 쓰기·요청 스레드를 남기지 않는다.
      writeGate.release();
      recording.join(WAIT_LIMIT.toMillis());
    }
    if (recording.isAlive()) throw new IllegalStateException("given 준비 실패: 사용 기록 스레드가 종료되지 않았다");
    return completed;
  }

  void recordDuringFailedFlush(TokenHash hash, Instant at) throws InterruptedException {
    failWrite.set(true);
    recordDuringFlush(hash, at);
    try {
      finishFlush();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof IllegalStateException cause
          && cause.getMessage().equals("usage-write-fixture")) return;
      throw failure;
    }
    throw new IllegalStateException("given 준비 실패: 붙잡은 사용 기록 쓰기가 실패해야 한다");
  }

  void finishFlush() {
    var running = flushing;
    flushing = Optional.empty();
    running.ifPresent(CompletableFuture::join);
  }

  private void beforeUsageWrite() {
    if (holdWrites.get()) writeGate.hold();
    if (failWrite.getAndSet(false)) throw new IllegalStateException("usage-write-fixture");
  }

  List<Duration> periods() {
    return scheduler.tasks().stream().map(ManualTaskScheduler.Scheduled::period).toList();
  }

  long scheduledTasks() {
    return scheduler.liveTasks();
  }

  List<Map<TokenHash, Instant>> writes() {
    return tokens.writes();
  }

  Optional<Instant> lastUsed(TokenHash hash) {
    return tokens.lastUsedAt(hash);
  }

  @Override
  public void close() {
    writeGate.release();
    finishFlush();
    recorder.close();
  }
}
