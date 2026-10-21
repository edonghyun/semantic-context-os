package io.scos.connector.tiro.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.RunErrorKind;

/**
 * Tiro 응답·체크포인트가 TS 어댑터가 일반 `Error`로 던지는 조건을 어겼다(봉투 위반, 커서 정지, 다른 워크스페이스의 노트, 범위가 바뀐 체크포인트 등). 공급자
 * 호출 바깥의 검사라 재시도하지 않는다. run에는 TS와 같은 errorKind "Error"를 남기고(CONTRACTS "run errorKind 매핑", F14-D1),
 * 메시지는 TS 메시지 그대로다. 내부 분류(invalid_provider_response·invalid_checkpoint)는 {@link #failure()}로 남긴다.
 */
final class TiroProtocolException extends RuntimeException implements RunErrorKind {
  /** TS가 일반 Error로 던지는 경우의 run errorKind(TS `error.constructor.name`). */
  static final String TS_ERROR_KIND = "Error";

  private final CallFailure failure;

  TiroProtocolException(CallFailure failure) {
    super(failure.message());
    this.failure = failure;
  }

  /** 내부 분류(종류·TS 메시지). */
  CallFailure failure() {
    return failure;
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
