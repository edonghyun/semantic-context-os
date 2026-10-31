package io.scos.engine.support.call;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 상태로 정하는 공급자 실패의 재시도 계약(Jira·Google·Tiro 공통): 429(또는 공급자가 본문으로 알린 요청 제한)는 요청 제한(Retry-After),
 * 401 인증 실패, 403·404 권한 없음, 그 밖의 4xx는 잘못된 공급자 응답(비재시도), 5xx는 의존 장애(재시도). HTTP 응답이 없는 실패의 문구는 커넥터마다
 * 달라 여기서 다루지 않는다.
 *
 * @param rateLimitReason 상태와 무관하게 요청 제한으로 볼 공급자 신호(Google 403 본문의 rate limit 이유). 없으면 false
 */
public record HttpStatusFailure(int status, boolean rateLimitReason) {
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int CLIENT_ERRORS = 400;
  private static final int SERVER_ERRORS = 500;

  public static HttpStatusFailure of(int status) {
    return new HttpStatusFailure(status, false);
  }

  public CallFailure toCallFailure(@Nullable String message, Optional<Duration> retryAfter) {
    if (status == TOO_MANY_REQUESTS || rateLimitReason)
      return CallFailure.rateLimited(message, retryAfter);
    if (status == UNAUTHORIZED) return CallFailure.of(Kind.AUTHENTICATION_FAILED, message);
    if (status == FORBIDDEN || status == NOT_FOUND)
      return CallFailure.of(Kind.PERMISSION_DENIED, message);
    if (status >= CLIENT_ERRORS && status < SERVER_ERRORS)
      return CallFailure.of(Kind.INVALID_PROVIDER_RESPONSE, message);
    return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, message);
  }
}
