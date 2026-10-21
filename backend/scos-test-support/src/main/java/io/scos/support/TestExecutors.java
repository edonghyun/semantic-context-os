package io.scos.support;

import java.util.concurrent.Executor;

/** 실행기 대역. 병렬 자체를 보지 않는 테스트는 호출 스레드에서 바로 실행한다(순서·결과가 순차 실행과 같다). */
public final class TestExecutors {
  private TestExecutors() {}

  public static final Executor CALLER_THREAD = Runnable::run;
}
