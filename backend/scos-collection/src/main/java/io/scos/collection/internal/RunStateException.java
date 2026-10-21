package io.scos.collection.internal;

/** run이 없거나 running이 아니거나 다른 소스의 것이다 — 호출자 버그(계약 위반), 재시도 불가. */
public final class RunStateException extends RuntimeException {
  public RunStateException(String message) {
    super(message);
  }
}
