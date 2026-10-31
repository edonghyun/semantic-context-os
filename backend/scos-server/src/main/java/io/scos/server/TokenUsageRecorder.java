package io.scos.server;

import io.scos.engine.EventLog;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/** 토큰별 사용 시각을 쓰기 연결에 배치 반영한다. */
final class TokenUsageRecorder implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TokenUsageRecorder.class);
  // 설계 §6.1: 요청별 쓰기를 피하고 분 단위로 반영한다.
  private static final Duration FLUSH_INTERVAL = Duration.ofMinutes(1);
  private final UserTokens tokens;
  private final Map<TokenHash, Instant> pending = new HashMap<>();
  private final ScheduledFuture<?> task;

  TokenUsageRecorder(UserTokens tokens, TaskScheduler scheduler) {
    this.tokens = tokens;
    task =
        scheduler.scheduleAtFixedRate(
            this::flush, scheduler.getClock().instant().plus(FLUSH_INTERVAL), FLUSH_INTERVAL);
  }

  void record(TokenHash hash, Instant at) {
    synchronized (pending) {
      pending.merge(hash, at, (previous, next) -> next.isAfter(previous) ? next : previous);
    }
  }

  // 주기·종료 반영은 직렬화하되, 요청 기록의 잠금은 저장소 쓰기 전에 놓는다.
  private synchronized void flush() {
    Map<TokenHash, Instant> batch;
    synchronized (pending) {
      if (pending.isEmpty()) return;
      batch = Map.copyOf(pending);
      pending.clear();
    }
    try {
      tokens.markUsed(batch);
    } catch (RuntimeException | Error failure) {
      batch.forEach(this::record);
      EventLog.error(LOG, "http.token_usage.failed").cause(failure).log();
      throw failure;
    }
  }

  @Override
  public void close() {
    task.cancel(false);
    flush();
  }
}
