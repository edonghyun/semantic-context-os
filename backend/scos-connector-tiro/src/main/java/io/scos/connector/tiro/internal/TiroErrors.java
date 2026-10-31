package io.scos.connector.tiro.internal;

import io.scos.engine.support.call.HttpStatusFailure;
import io.scos.spi.connector.CallFailure;

/**
 * Tiro 오류의 재시도 계약: HTTP 응답이 있으면 상태로({@link HttpStatusFailure} — 401 인증 실패, 403·404 권한 없음, 429 요청
 * 제한(Retry-After), 그 밖의 4xx는 비재시도, 5xx는 재시도), 응답이 없는 실패는 "Tiro transport failed"(재시도). 응답 본문·키를 싣지
 * 않는다.
 */
final class TiroErrors {
  static final int FORBIDDEN = 403;
  static final int NOT_FOUND = 404;

  private TiroErrors() {}

  static CallFailure classify(Exception error) {
    if (!(error instanceof TiroApiException api))
      return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "Tiro transport failed");
    return HttpStatusFailure.of(api.status()).toCallFailure(api.getMessage(), api.retryAfter());
  }
}
