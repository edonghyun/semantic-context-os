package io.scos.connector.jira.internal;

import io.scos.spi.connector.RunErrorKind;

/** Jira 응답이 프로토콜을 어겼다(봉투 누락·잘림, 검색 커서 정지, 식별자 없음). 재시도하지 않는다. 메시지 문구는 계약이라 바꾸지 않는다. */
public final class JiraProtocolException extends RuntimeException implements RunErrorKind {
  /** 프로토콜 위반의 run errorKind(JS `error.constructor.name` 값, CONTRACTS "run errorKind 매핑"). */
  public static final String TS_ERROR_KIND = "Error";

  JiraProtocolException(String message) {
    super(message);
  }

  @Override
  public String runErrorKind() {
    return TS_ERROR_KIND;
  }
}
