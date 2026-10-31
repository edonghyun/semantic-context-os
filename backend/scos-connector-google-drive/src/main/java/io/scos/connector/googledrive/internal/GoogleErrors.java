package io.scos.connector.googledrive.internal;

import io.scos.engine.support.PureFunctions;
import io.scos.engine.support.call.HttpStatusFailure;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.CallFailureException;

/**
 * Google 오류의 재시도 계약: HTTP 응답이 있으면 상태로({@link HttpStatusFailure} — 429 또는 403 본문의 rate limit 이유는 요청
 * 제한(Retry-After), 401 인증 실패, 403·404 권한 없음, 그 밖의 4xx는 비재시도, 5xx는 재시도), 응답이 없는 실패(전송·리다이렉트·경로·본문
 * 상한)는 의존 장애(재시도, "Google transport failed").
 */
@PureFunctions
public final class GoogleErrors {
  static final String TRANSPORT_FAILED = "Google transport failed";

  private GoogleErrors() {}

  /** 이미 분류된 실패(헤더 지점의 자격 증명 설정 오류 등)는 다시 분류하지 않고 그대로 던진다. */
  public static CallFailure classify(Exception error) {
    if (error instanceof CallFailureException classified) throw classified;
    if (!(error instanceof GoogleApiException api))
      return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, TRANSPORT_FAILED);
    return new HttpStatusFailure(api.status(), api.rateLimited())
        .toCallFailure(api.getMessage(), api.retryAfter());
  }
}
