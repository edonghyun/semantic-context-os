package io.scos.support;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 인터럽트로 깨지지 않는 작업(예: JDBC 소켓 읽기)의 모사: hold()를 부른 스레드는 release()까지 멈추고, 그 사이 받은 인터럽트 표시는 되돌린다. 놓이지
 * 않으면 상한(hang 방지) 뒤 예외. 동사는 RequestGate와 같다(hold / awaitHeld / release).
 */
public final class HoldGate {
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);
  private final Duration waitLimit;

  public HoldGate(Duration waitLimit) {
    this.waitLimit = waitLimit;
  }

  /** 붙잡히는 쪽: 놓일 때까지 기다린다. */
  public void hold() {
    entered.countDown();
    boolean interrupted = false;
    while (true) {
      try {
        if (released.await(waitLimit.toMillis(), TimeUnit.MILLISECONDS)) break;
        throw new IllegalStateException("hold gate was never released");
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  /** 누군가 hold()에 들어갈 때까지 기다린다. */
  public void awaitHeld(String what) {
    Eventually.await(what, () -> entered.getCount() == 0, waitLimit);
  }

  public void release() {
    released.countDown();
  }
}
