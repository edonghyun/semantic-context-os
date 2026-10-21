package io.scos.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * worker 실행기 대역: 고정 스레드 풀에 위임하며 제출 수(거부된 제출 포함)와 끝나지 않은 작업 수를 센다. 제출이 상한을 넘으면 AssertionError로 멈춘다 —
 * 끝나지 않는 제출 루프가 큐로 힙을 채워 hang·메모리 오류가 되지 않고 테스트 실패가 되게(MUTATION.md).
 */
public final class CountedWorkers extends AbstractExecutorService {
  /** 한 실행기에 제출할 수 있는 작업 수 — hang 방지용이며 성공 근거가 아니다. */
  public static final int SUBMISSION_LIMIT = 10_000;

  private final ExecutorService delegate;
  private final AtomicInteger submitted = new AtomicInteger();
  private final AtomicInteger unfinished = new AtomicInteger();

  private CountedWorkers(ExecutorService delegate) {
    this.delegate = delegate;
  }

  public static CountedWorkers of(int threads) {
    return new CountedWorkers(Executors.newFixedThreadPool(threads));
  }

  @Override
  public void execute(Runnable command) {
    if (submitted.incrementAndGet() > SUBMISSION_LIMIT)
      throw new AssertionError("worker 제출이 상한 " + SUBMISSION_LIMIT + "을 넘었다 — 제출이 끝나지 않는다");
    unfinished.incrementAndGet();
    try {
      delegate.execute(
          () -> {
            try {
              command.run();
            } finally {
              unfinished.decrementAndGet();
            }
          });
    } catch (RejectedExecutionException e) {
      unfinished.decrementAndGet();
      throw e;
    }
  }

  /** 지금까지 제출한 작업 수(거부된 제출 포함). */
  public int submitted() {
    return submitted.get();
  }

  public void resetSubmitted() {
    submitted.set(0);
  }

  /** 제출한 작업이 전부 끝날 때까지(작업 안의 마지막 문장까지). */
  public void awaitIdle(Duration waitLimit) {
    Eventually.await("worker 작업이 모두 끝남", () -> unfinished.get() == 0, waitLimit);
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }
}
