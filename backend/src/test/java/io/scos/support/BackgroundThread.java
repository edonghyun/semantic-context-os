package io.scos.support;

import java.time.Duration;

/** 다른 스레드에서 부르는 호출 하나: 대기 상태 확인, 인터럽트, 반환 여부, 끝난 뒤 인터럽트 표시. 동기화 코드를 테스트 본문 밖에 둔다. */
public final class BackgroundThread {
  /** 인터럽트로 끝날 수 있는 호출. */
  public interface Call {
    void run() throws InterruptedException;
  }

  private final Thread thread;
  private volatile boolean returned;
  private volatile boolean interruptedAtEnd;

  private BackgroundThread(String name, Call call) {
    thread =
        new Thread(
            () -> {
              try {
                call.run();
                returned = true;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              interruptedAtEnd = Thread.currentThread().isInterrupted();
            },
            name);
    thread.start();
  }

  public static BackgroundThread start(String name, Call call) {
    return new BackgroundThread(name, call);
  }

  /** 스레드가 대기(WAITING·TIMED_WAITING)에 들어갔거나 이미 끝났다. */
  public boolean isBlockedOrDone() {
    Thread.State state = thread.getState();
    return state == Thread.State.WAITING
        || state == Thread.State.TIMED_WAITING
        || state == Thread.State.TERMINATED;
  }

  /**
   * 스레드가 owner.method 안에서 대기(WAITING·TIMED_WAITING) 중이다 — "어떤 시간 대기든"이 아니라 이름 있는 대기 지점(예: {@code
   * CollectionDaemon.stop}의 합류, {@code CancellationToken.awaitCancellation}의 재시도 대기)을 본다.
   */
  public boolean isWaitingIn(Class<?> owner, String method) {
    Thread.State state = thread.getState();
    if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
    for (StackTraceElement frame : thread.getStackTrace())
      if (frame.getClassName().equals(owner.getName()) && frame.getMethodName().equals(method))
        return true;
    return false;
  }

  /**
   * 인터럽트하고 끝나기를 기다린다. 상한 안에 끝나지 않으면 멈춘다 — 그 뒤 hasReturned()가 "인터럽트로 끝남"과 "아직 도는 중"을 구분하지 못하기
   * 때문이다(F15 T15.2, tr-vocab-harness High 4).
   */
  public void interruptAndJoin(Duration waitLimit) throws InterruptedException {
    thread.interrupt();
    thread.join(waitLimit.toMillis());
    if (thread.isAlive())
      throw new IllegalStateException(
          "given 준비 실패: 스레드 " + thread.getName() + "가 인터럽트 뒤 " + waitLimit + " 안에 끝나지 않았다");
  }

  /** 호출이 정상 반환했다(인터럽트 예외로 끝난 것은 아니다). */
  public boolean hasReturned() {
    return returned;
  }

  public boolean returnsWithin(Duration d) throws InterruptedException {
    thread.join(d.toMillis());
    return returned;
  }

  public boolean isAlive() {
    return thread.isAlive();
  }

  /** 호출이 끝난 시점에 그 스레드의 인터럽트 표시가 남아 있었다. */
  public boolean wasInterruptedAtEnd() {
    return interruptedAtEnd;
  }
}
