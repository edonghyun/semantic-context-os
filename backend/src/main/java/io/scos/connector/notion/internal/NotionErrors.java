package io.scos.connector.notion.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import java.util.Set;

/**
 * Notion 오류의 재시도 계약(TS classifyNotionError): 코드로 나눈다 — unauthorized·invalid_grant는 인증 실패,
 * restricted_resource·object_not_found는 권한 없음, 응답 형식 위반·불완전한 목록·validation_error는 잘못된 공급자 응답(비재시도),
 * rate_limited는 요청 제한(Retry-After), 그 밖의 코드(5xx·conflict_error·http_error)와 전송 실패는 의존 장애(재시도). HTTP
 * 응답이 없는 실패의 메시지는 예외 메시지다(TS `error.message` — 문구는 저장값 비교 대상이 아니다).
 */
final class NotionErrors {
  static final String OBJECT_NOT_FOUND = "object_not_found";
  static final String RATE_LIMITED = "rate_limited";
  private static final String TRANSPORT_FAILED = "Notion request failed";
  private static final Set<String> AUTHENTICATION = Set.of("unauthorized", "invalid_grant");
  private static final Set<String> PERMISSION = Set.of("restricted_resource", OBJECT_NOT_FOUND);
  private static final Set<String> INVALID_RESPONSE =
      Set.of(
          NotionApiException.INVALID_PROVIDER_RESPONSE,
          "incomplete_search_results",
          "incomplete_query_results",
          "validation_error");

  private NotionErrors() {}

  static CallFailure classify(Exception error) {
    if (!(error instanceof NotionApiException api))
      return CallFailure.of(
          Kind.DEPENDENCY_UNAVAILABLE,
          error.getMessage() == null ? TRANSPORT_FAILED : error.getMessage());
    String code = api.code();
    String message = api.getMessage();
    if (AUTHENTICATION.contains(code)) return CallFailure.of(Kind.AUTHENTICATION_FAILED, message);
    if (PERMISSION.contains(code)) return CallFailure.of(Kind.PERMISSION_DENIED, message);
    if (INVALID_RESPONSE.contains(code))
      return CallFailure.of(Kind.INVALID_PROVIDER_RESPONSE, message);
    if (code.equals(RATE_LIMITED)) return CallFailure.rateLimited(message, api.retryAfter());
    return CallFailure.of(Kind.DEPENDENCY_UNAVAILABLE, message);
  }
}
