package io.scos.spi.connector;

/** 재시도 정책을 다 쓴(또는 비재시도) 공급자 호출 실패. 런타임은 failure.kind를 run errorKind로 기록한다. */
public final class CallFailureException extends RuntimeException {
  private final String operation;
  private final CallFailure failure;

  public CallFailureException(String operation, CallFailure failure) {
    super(operation + ": " + failure.kind().wire() + " — " + failure.message());
    this.operation = operation;
    this.failure = failure;
  }

  public String operation() {
    return operation;
  }

  public CallFailure failure() {
    return failure;
  }
}
