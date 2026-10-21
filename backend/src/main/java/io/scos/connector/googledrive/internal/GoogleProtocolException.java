package io.scos.connector.googledrive.internal;

import io.scos.spi.connector.RunErrorKind;

/**
 * TS Drive 어댑터·클라이언트가 일반 `Error`로 던지는 경우(자격 증명 JSON, 계정 불일치, 목록·피드 봉투 위반, 토큰 정지, 잘못된 체크포인트 등). 공급자
 * 호출 바깥의 검사라 재시도하지 않는다. run에는 TS와 같은 errorKind "Error"를 남기고(CONTRACTS "run errorKind 매핑"), 메시지는 TS
 * 메시지 그대로다 — 비밀값·응답 본문을 싣지 않는다.
 */
public final class GoogleProtocolException extends RuntimeException implements RunErrorKind {
  /** TS가 일반 Error로 던지는 경우의 run errorKind(TS `error.constructor.name`). */
  static final String TS_ERROR_KIND = "Error";

  GoogleProtocolException(String message) {
    super(message);
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
