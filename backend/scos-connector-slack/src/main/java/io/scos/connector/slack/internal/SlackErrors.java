package io.scos.connector.slack.internal;

import io.scos.spi.connector.CallFailure;

/**
 * Slack 오류의 재시도 계약: error 코드로 가른다. invalid_auth 인증 실패, missing_scope 권한 없음, 형식 위반(어긋난 검사와 함께)·사라진
 * 스레드는 잘못된 공급자 응답(비재시도), ratelimited는 요청 제한(Retry-After), 그 밖의 코드와 전송 실패는 의존 장애(재시도).
 */
final class SlackErrors {
  private SlackErrors() {}

  static CallFailure classify(Exception error) {
    if (!(error instanceof SlackApiException api))
      return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "Slack request failed");
    String message = api.getMessage();
    return switch (api.code()) {
      case "invalid_auth" -> CallFailure.of(CallFailure.Kind.AUTHENTICATION_FAILED, message);
      case "missing_scope" -> CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, message);
      case SlackApiException.INVALID_PROVIDER_RESPONSE ->
          CallFailure.invalidResponse(message, api.violation());
      case "thread_not_found" ->
          CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message);
      case "ratelimited" -> CallFailure.rateLimited(message, api.retryAfter());
      default -> CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, message);
    };
  }
}
