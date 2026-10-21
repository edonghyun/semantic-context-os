package io.scos.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * 수동 tick 스케줄러(결정적 heartbeat 테스트): 등록된 고정 주기 작업을 {@link #tick()}이 호출 스레드에서 한 번씩 실행한다. 취소된 작업은 건너뛴다.
 */
public final class ManualTaskScheduler implements TaskScheduler {
  public record Scheduled(Runnable task, Duration period, Handle handle) {}

  public static final class Handle extends CompletableFuture<Object>
      implements ScheduledFuture<Object> {
    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }
  }

  private final List<Scheduled> tasks = new ArrayList<>();
  private final String rejectReason;

  public ManualTaskScheduler() {
    this(null);
  }

  private ManualTaskScheduler(String rejectReason) {
    this.rejectReason = rejectReason;
  }

  /** 모든 등록을 {@link TaskRejectedException}으로 거부하는 스케줄러(종료 중·포화 모사). */
  public static ManualTaskScheduler rejecting(String reason) {
    return new ManualTaskScheduler(reason);
  }

  public synchronized List<Scheduled> tasks() {
    return List.copyOf(tasks);
  }

  /** 취소되지 않아 풀 슬롯을 쥐고 있는 작업 수 — 실제 스케줄러라면 계속 실행될 것들. */
  public synchronized long liveTasks() {
    return tasks.stream().filter(t -> !t.handle().isCancelled()).count();
  }

  /** 취소되지 않은 작업을 등록 순으로 한 번씩 실행한다. */
  public void tick() {
    List<Scheduled> snapshot = tasks();
    for (Scheduled s : snapshot) if (!s.handle().isCancelled()) s.task().run();
  }

  @Override
  public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
    if (rejectReason != null) throw new TaskRejectedException(rejectReason);
    Handle h = new Handle();
    tasks.add(new Scheduled(task, period, h));
    return h;
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
    return scheduleAtFixedRate(task, period);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
    return scheduleAtFixedRate(task, delay);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable task, Instant startTime, Duration delay) {
    return scheduleAtFixedRate(task, delay);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
    throw new UnsupportedOperationException("trigger scheduling is not used");
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
    throw new UnsupportedOperationException("one-shot scheduling is not used");
  }
}
