package io.scos.connector.jira.internal;

import io.scos.spi.connector.RunErrorKind;

/**
 * Jira 응답이 프로토콜을 어겼다(TS가 던지는 일반 Error와 같은 경우 — 봉투 누락·잘림, 검색 커서 정지, 식별자 없음). 재시도하지 않는다. 메시지는 TS와 같다.
 */
public final class JiraProtocolException extends RuntimeException implements RunErrorKind {
  /** TS가 일반 Error로 던지는 경우의 run errorKind(TS `error.constructor.name`). */
  public static final String TS_ERROR_KIND = "Error";

  JiraProtocolException(String message) {
    super(message);
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
