package io.scos.spi.connector;

import java.util.Optional;

/**
 * 재시도 정책을 다 쓴(또는 비재시도) 공급자 호출 실패. 런타임은 failure.kind를 run errorKind로 기록한다. 커넥터가 실패한 작업의 공급자 위치를 알면
 * {@link #at}으로 붙인다 — 런타임이 실패 이벤트의 providerLocator로 옮긴다(메시지·분류는 그대로).
 */
public final class CallFailureException extends RuntimeException {
  private final String operation;
  private final CallFailure failure;
  private final Optional<String> providerLocatorJson;

  public CallFailureException(String operation, CallFailure failure) {
    this(operation, failure, Optional.empty());
  }

  private CallFailureException(
      String operation, CallFailure failure, Optional<String> providerLocatorJson) {
    super(operation + ": " + failure.kind().wire() + " — " + failure.message());
    this.operation = operation;
    this.failure = failure;
    this.providerLocatorJson = providerLocatorJson;
  }

  public String operation() {
    return operation;
  }

  public CallFailure failure() {
    return failure;
  }

  /** 실패한 곳의 공급자 위치(JSON 객체 텍스트, {@link RejectedItem#providerLocatorJson}과 같은 표현). */
  public Optional<String> providerLocatorJson() {
    return providerLocatorJson;
  }

  /** 같은 실패에 공급자 위치를 붙인 사본. 스택·억제된 예외도 옮긴다. */
  public CallFailureException at(String providerLocatorJson) {
    CallFailureException located =
        new CallFailureException(operation, failure, Optional.of(providerLocatorJson));
    located.setStackTrace(getStackTrace());
    for (Throwable suppressed : getSuppressed()) located.addSuppressed(suppressed);
    return located;
  }
}
