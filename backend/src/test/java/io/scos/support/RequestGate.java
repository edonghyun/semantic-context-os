package io.scos.support;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * 가짜 공급자의 요청 하나를 붙잡아 두는 게이트(테스트 컨텍스트 공용): 경로에 문자열이 들어간 첫 요청을 {@link #release()}까지 멈춘다. 대상은 {@code
 * gateOn(pathContains, latch, onArrive)}를 가진 가짜(`FakeHttpServer`, 그 위의 커넥터 가짜)다. 닫으면 붙잡은 요청을 놓는다.
 * 동사는 HoldGate와 같다(hold / awaitHeld / release).
 */
public final class RequestGate implements AutoCloseable {
  /** 게이트를 거는 가짜 공급자. */
  @FunctionalInterface
  public interface Target {
    void gateOn(String pathContains, CountDownLatch latch, Consumer<String> onArrive);
  }

  private final Target target;
  private final Duration waitLimit;
  private CountDownLatch gate;
  private CountDownLatch arrived;

  public RequestGate(Target target, Duration waitLimit) {
    this.target = target;
    this.waitLimit = waitLimit;
  }

  /** 경로에 문자열이 포함된 첫 요청을 release()까지 붙잡는다. */
  public void hold(String pathContains) {
    gate = new CountDownLatch(1);
    arrived = new CountDownLatch(1);
    target.gateOn(pathContains, gate, path -> arrived.countDown());
  }

  /** 붙잡을 요청이 게이트에 올 때까지(무엇을 기다리는지 이름으로). 상한은 hang 방지용이다. */
  public void awaitHeld(String what) {
    Eventually.await(what, () -> arrived.getCount() == 0, waitLimit);
  }

  public void release() {
    gate.countDown();
  }

  /** 붙잡아 둔 요청을 놓고 이후 요청은 붙잡지 않는다. */
  public void stopHolding() {
    release();
    target.gateOn("", new CountDownLatch(0), path -> {});
  }

  @Override
  public void close() {
    if (gate != null) gate.countDown();
  }
}
