package io.scos.connector.jira.internal;

import io.scos.engine.support.call.HttpStatusFailure;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;

/**
 * Jira 오류의 재시도 계약: HTTP 응답이 있으면 상태로({@link HttpStatusFailure} — 401 인증 실패, 403·404 권한 없음, 429 요청
 * 제한(Retry-After), 그 밖의 4xx는 비재시도, 5xx는 재시도), 응답이 없는 실패는 "Jira transport failed"(재시도).
 */
final class JiraErrors {
  static final String TRANSPORT_FAILED = "Jira transport failed";

  private JiraErrors() {}

  static CallFailure classify(Exception error) {
    if (!(error instanceof JiraApiException api))
      return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, TRANSPORT_FAILED);
    return HttpStatusFailure.of(api.status()).toCallFailure(api.getMessage(), api.retryAfter());
  }
}
