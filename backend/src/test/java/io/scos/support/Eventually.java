package io.scos.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 실제 조건을 기다리는 유일한 진입점(TESTING §7). 폴링은 ChildJvm.awaitCondition(Awaitility를 쓰는 유일한 곳)에 위임한다. 인터럽트되면
 * 표시를 되살리고 무엇을 기다리다 끊겼는지 밝혀 실패한다. 테스트 본문은 이 클래스도 직접 부르지 않고 시나리오의 await…() 메서드를 부른다.
 */
public final class Eventually {
  private Eventually() {}

  public static void await(String condition, BooleanSupplier met, Duration waitLimit) {
    try {
      ChildJvm.awaitCondition(condition, met, waitLimit.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for: " + condition, e);
    }
  }
}
