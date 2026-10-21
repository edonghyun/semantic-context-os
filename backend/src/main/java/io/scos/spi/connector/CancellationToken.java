package io.scos.spi.connector;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 명시 취소(ADR-0008): 공급자 호출·재시도 대기·폴링이 이 토큰을 본다. 대기는 취소로 즉시 깨어난다 — Thread.sleep 폴링이 아니다. */
public final class CancellationToken {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private volatile boolean cancelled;

  public boolean isCancelled() {
    return cancelled;
  }

  public void cancel() {
    lock.lock();
    try {
      cancelled = true;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /** 최대 d만큼 기다린다. 취소되면(대기 전·중) 즉시 true. 인터럽트는 취소로 취급한다. */
  public boolean awaitCancellation(Duration d) {
    if (cancelled) return true;
    lock.lock();
    try {
      long nanos = TimeUnit.MILLISECONDS.toNanos(d.toMillis());
      while (!cancelled && nanos > 0) nanos = changed.awaitNanos(nanos);
      return cancelled;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    } finally {
      lock.unlock();
    }
  }

  public static CancellationToken none() {
    return new CancellationToken();
  }
}
