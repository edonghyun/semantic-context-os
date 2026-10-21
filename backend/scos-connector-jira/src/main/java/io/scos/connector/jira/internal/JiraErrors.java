package io.scos.connector.jira.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;

/**
 * Jira 오류의 재시도 계약(TS classifyJiraError): 401 인증 실패, 403·404 권한 없음, 429 요청 제한(Retry-After), 그 밖의
 * 4xx는 잘못된 공급자 응답(비재시도), 5xx·전송 실패는 의존 장애(재시도). HTTP 응답이 없는 실패는 "Jira transport failed".
 */
final class JiraErrors {
  static final String TRANSPORT_FAILED = "Jira transport failed";
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int CLIENT_ERRORS = 400;
  private static final int SERVER_ERRORS = 500;

  private JiraErrors() {}

  static CallFailure classify(Exception error) {
    if (!(error instanceof JiraApiException api))
      return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, TRANSPORT_FAILED);
    int status = api.status();
    String message = api.getMessage();
    if (status == UNAUTHORIZED) return CallFailure.of(Kind.AUTHENTICATION_FAILED, message);
    if (status == FORBIDDEN || status == NOT_FOUND)
      return CallFailure.of(Kind.PERMISSION_DENIED, message);
    if (status == TOO_MANY_REQUESTS) return CallFailure.rateLimited(message, api.retryAfter());
    if (status >= CLIENT_ERRORS && status < SERVER_ERRORS)
      return CallFailure.of(Kind.INVALID_PROVIDER_RESPONSE, message);
    return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, message);
  }
}
