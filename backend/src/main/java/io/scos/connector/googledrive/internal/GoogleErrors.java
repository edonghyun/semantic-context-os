package io.scos.connector.googledrive.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.CallFailureException;

/**
 * Google 오류의 재시도 계약(TS classifyGoogleError): 429 또는 403 본문의 rate limit 이유는 요청 제한(Retry-After), 401
 * 인증 실패, 403·404 권한 없음, 그 밖의 4xx는 잘못된 공급자 응답(비재시도), 5xx·HTTP 응답이 없는 실패(전송·리다이렉트·경로·본문 상한)는 의존
 * 장애(재시도, "Google transport failed").
 */
public final class GoogleErrors {
  static final String TRANSPORT_FAILED = "Google transport failed";
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int CLIENT_ERRORS = 400;
  private static final int SERVER_ERRORS = 500;

  private GoogleErrors() {}

  /** 이미 분류된 실패(헤더 지점의 자격 증명 설정 오류 등)는 다시 분류하지 않고 그대로 던진다. */
  public static CallFailure classify(Exception error) {
    if (error instanceof CallFailureException classified) throw classified;
    if (!(error instanceof GoogleApiException api))
      return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, TRANSPORT_FAILED);
    int status = api.status();
    String message = api.getMessage();
    if (status == TOO_MANY_REQUESTS || api.rateLimited())
      return CallFailure.rateLimited(message, api.retryAfter());
    if (status == UNAUTHORIZED) return CallFailure.of(Kind.AUTHENTICATION_FAILED, message);
    if (status == FORBIDDEN || status == NOT_FOUND)
      return CallFailure.of(Kind.PERMISSION_DENIED, message);
    if (status >= CLIENT_ERRORS && status < SERVER_ERRORS)
      return CallFailure.of(Kind.INVALID_PROVIDER_RESPONSE, message);
    return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, message);
  }
}
