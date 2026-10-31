package io.scos.connector.tiro.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.RunErrorKind;

/**
 * Tiro 응답·체크포인트가 프로토콜 조건을 어겼다(봉투 위반, 커서 정지, 다른 워크스페이스의 노트, 범위가 바뀐 체크포인트 등). 공급자 호출 바깥의 검사라 재시도하지
 * 않는다. run에는 errorKind "Error"를 남기고(CONTRACTS "run errorKind 매핑", F14-D1), 메시지 문구는 계약이라 바꾸지 않는다. 내부
 * 분류(invalid_provider_response·invalid_checkpoint)는 {@link #failure()}로 남긴다.
 */
final class TiroProtocolException extends RuntimeException implements RunErrorKind {
  /** 프로토콜 위반의 run errorKind(JS `error.constructor.name` 값). */
  static final String TS_ERROR_KIND = "Error";

  private final CallFailure failure;

  TiroProtocolException(CallFailure failure) {
    super(failure.message());
    this.failure = failure;
  }

  /**
   * 응답 형식 위반(invalid_provider_response). 투영이 읽는 필드를 체크포인트 전진 전에 확인한다 — 그 노트를 불완전한 본문으로 덮어쓰지 않고 run이
   * 실패한다(TS는 일반 Error로 errorKind "Error", Java는 종류로 분류).
   */
  static TiroProtocolException invalid(String message) {
    return new TiroProtocolException(
        CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message));
  }

  /** 내부 분류(종류·메시지). */
  CallFailure failure() {
    return failure;
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
